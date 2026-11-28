(ns docker-benchmark-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(load-file (str (fs/normalize (fs/path (fs/parent (fs/absolutize *file*)) "../../../../run-benchmark.bb"))))
(alias 'benchmark 'docker-benchmark)

(defn baseline []
  {:status "completed" :configuration {:user "mcp"}
   :rows (mapv (fn [protocol] {:size "10KiB" :protocol protocol :concurrency 1 :bytes 10240 :sha256 "hash"
                              :throughputMiBPerSecond 100.0 :p95Ms 10.0 :attempts 100}) ["rest" "grpc"])})

(deftest regression-detection
  (let [old (baseline)
        slow (update old :rows #(mapv (fn [row] (assoc row :throughputMiBPerSecond 70.0)) %))
        latency (assoc-in old [:rows 1 :p95Ms] 15.0)]
    (testing "Both protocols slowing equally is a regression despite an unchanged ratio"
      (is (every? :regression (benchmark/compare-baseline slow old 15))))
    (testing "p95 detects a regression even without throughput loss"
      (is (= [false true] (mapv :regression (benchmark/compare-baseline latency old 15)))))
    (testing "An unchanged run passes"
      (is (not-any? :regression (benchmark/compare-baseline old old 15))))))

(deftest reject-incomparable-baselines
  (doseq [old [(assoc (baseline) :status "failed")
               (assoc-in (baseline) [:configuration :user] "admin")
               (assoc-in (baseline) [:rows 0 :sha256] "changed")
               (update (baseline) :rows pop)]]
    (is (thrown? clojure.lang.ExceptionInfo (benchmark/compare-baseline (baseline) old 15)))))

(deftest incomplete-or-corrupt-measurements-fail
  (let [fixture {:label "10KiB" :bytes 10240 :sha256 "hash"}
        cells (vec (for [p ["rest" "grpc"] r [1 2 3]]
                     {:protocol p :concurrency 1.0 :repetition (double r) :errors {} :successes 4.0 :attempts 4.0
                      :contentMiBPerSecond (* r 10.0) :successfulOperationsPerSecond 10.0 :latencyMs {:p95 5.0}}))
        report {:status "completed" :corpus [{:binary {:bytes 10240.0 :sha256 "hash"}}] :runs cells}]
    (testing "Numbers from the Protobuf JSON harness can be floating point"
      (is (= [20.0 20.0] (mapv :throughputMiBPerSecond (benchmark/summarize report fixture 3 [1])))))
    (doseq [invalid [(update report :runs pop)
                     (assoc-in report [:corpus 0 :binary :sha256] "changed")
                     (assoc-in report [:runs 0 :errors] {:HTTP_500 1})
                     (assoc report :status "aborted")]]
      (is (thrown? clojure.lang.ExceptionInfo (benchmark/summarize invalid fixture 3 [1]))))))

(deftest reproducible-fixtures-without-overwriting-existing-nodes
  (let [directory (fs/create-temp-dir)
        nodes (atom {}) uploads (atom 0)]
    (try
      (with-redefs [benchmark/sizes [["10KiB" 10240] ["1MiB" 1048577]]
                    benchmark/child! (fn [_ _ name folder?] (if folder? {:id "folder"} (get @nodes name)))
                    benchmark/upload! (fn [_ _ path]
                                        (swap! uploads inc)
                                        (let [name (str (fs/file-name path)) entry {:id name :content {:sizeInBytes (fs/size path)}}]
                                          (swap! nodes assoc name entry)
                                          entry))]
        (let [created (benchmark/fixtures! {} directory)
              second (benchmark/fixtures! {} directory)]
          (is (= created second))
          (is (= 2 @uploads))
          (is (empty? (fs/list-dir directory)))
          (swap! nodes assoc-in [(:id (first created)) :content :sizeInBytes] 1)
          (is (thrown? clojure.lang.ExceptionInfo (benchmark/fixtures! {} directory)))
          (is (= 2 @uploads))))
      (finally (fs/delete-tree directory)))))

(deftest teardown-on-failure-and-interruption
  (doseq [interrupted? [false true]]
    (let [directory (fs/create-temp-dir)
          args {:output (str directory)}
          summary (atom (assoc (baseline) :stackAttempted true :status "failed"))
          calls (atom []) kills (atom []) closing? (atom false)
          context {:compose ["docker" "compose"] :processes (atom #{:child})}]
      (try
        (with-redefs [benchmark/command! (fn [_ argv & _] (swap! calls conj argv))
                      babashka.process/destroy-tree (fn [task] (swap! kills conj task))]
          (benchmark/finish! context args summary closing? interrupted?)
          (benchmark/finish! context args summary closing? interrupted?)
          (is (= 1 (count (filter #(some #{"down"} %) @calls))))
          (is (not-any? #(some #{"-v" "--volumes"} %) @calls))
          (is (= "stopped" (:cleanup @summary)))
          (is (= (if interrupted? "interrupted" "failed") (:status @summary)))
          (is (= (if interrupted? [:child] []) @kills))
          (is (str/includes? (slurp (str (fs/path directory "report.md"))) (str "**" (:status @summary) "**"))))
        (finally (fs/delete-tree directory))))))

(deftest cleanup-failures-are-reported
  (let [directory (fs/create-temp-dir) summary (atom (assoc (baseline) :stackAttempted true))]
    (try
      (with-redefs [benchmark/command! (fn [_ argv & _] (when (some #{"down"} argv) (throw (Exception. "failure"))))]
        (benchmark/finish! {:compose ["docker" "compose"] :processes (atom #{})} {:output (str directory)} summary (atom false) false)
        (is (= "cleanup_failed" (:status @summary)))
        (is (str/includes? (:cleanup @summary) "FAILED")))
      (finally (fs/delete-tree directory)))))

(deftest reject-unsafe-options
  (is (= [1 4] (:workers (benchmark/options []))))
  (doseq [args [["--concurrency=1,1"] ["--concurrency=8"] ["--threshold=0"]
                ["--requests=0"] ["--unknown=x"] ["--output=protoalf-client/target/results"]]]
    (is (thrown? Exception (benchmark/options args)))))

(deftest real-process-output-timeouts-and-cancellation
  (let [directory (fs/create-temp-dir)
        context {:processes (atom #{}) :cancelled? (atom false)}]
    (try
      (is (= "ok" (benchmark/capture! context ["bb" "-e" "(print \"ok\")"])))
      (is (thrown? clojure.lang.ExceptionInfo
                   (benchmark/command! context ["bb" "-e" "(Thread/sleep 5000)"]
                                       (fs/path directory "timeout.log") {:timeout 1})))
      (is (empty? @(:processes context)))
      (reset! (:cancelled? context) true)
      (is (thrown? clojure.lang.ExceptionInfo (benchmark/capture! context ["bb" "-e" "(print :unexpected)"])))
      (finally (fs/delete-tree directory)))))

(deftest listing-metrics-use-nodes-per-second-instead-of-content-bytes
  (let [old (update (baseline) :rows #(mapv (fn [row] (assoc row :size "listing" :scenario "list"
                                                         :bytes 0 :throughputMiBPerSecond 0 :nodesPerSecond 100)) %))
        slow (assoc-in old [:rows 1 :nodesPerSecond] 50)]
    (is (= [false true] (mapv :regression (benchmark/compare-baseline slow old 15))))))

(deftest listing-fixture-is-idempotent-and-detects-modified-metadata
  (let [entries (atom []) posts (atom 0)
        args {:listing-items 5 :page-size 2}]
    (with-redefs [benchmark/child! (fn [& _] {:id "folder"})
                  benchmark/listing-entries! (fn [& _] (vec (sort-by :name @entries)))
                  benchmark/request! (fn [_ method _ body]
                                       (is (= :post method))
                                       (swap! posts inc)
                                       (swap! entries conj {:id (:name body) :name (:name body) :nodeType (:nodeType body)
                                                            :aspectNames (:aspectNames body)
                                                            :properties (into {} (map (fn [[k v]] [(keyword k) v]) (:properties body)))})
                                       {:entry (last @entries)})]
      (let [created (benchmark/listing-fixture! {} args)
            reused (benchmark/listing-fixture! {} args)]
        (is (= created reused))
        (is (= 5 @posts))
        (is (= 5 (:items created)))
        (swap! entries assoc-in [0 :properties :cm:description] "changed")
        (is (thrown? clojure.lang.ExceptionInfo (benchmark/listing-fixture! {} args)))
        (is (= 5 @posts))))))

(deftest no-count-profile-does-not-require-or-compare-rest-samples
  (let [fixture {:label "listing-no-count" :scenario "list-no-count" :protocols ["grpc"] :bytes 0 :sha256 "folder"}
        report {:status "completed" :corpus []
                :runs (mapv (fn [repetition] {:protocol "grpc" :concurrency 1.0 :repetition repetition
                                              :errors {} :successes 10 :attempts 10 :contentMiBPerSecond 0
                                              :successfulUnitsPerSecond 1000 :successfulOperationsPerSecond 10
                                              :latencyMs {:p95 100}}) [1 2 3])}
        rows (benchmark/summarize report fixture 3 [1])]
    (is (= 1 (count rows)))
    (is (= "grpc" (:protocol (first rows))))
    (is (= 1000.0 (benchmark/throughput (first rows))))))
