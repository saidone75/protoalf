#!/usr/bin/env bb
(ns docker-benchmark
  "Docker benchmark lifecycle, fixtures, reports and regression checks. Run directly with ./run-benchmark.bb."
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io ByteArrayInputStream SequenceInputStream]
           [java.net URLEncoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]
           [java.time Instant]
           [java.util Collections HexFormat Random UUID]
           [java.util.concurrent TimeUnit]))

(def repo (str (fs/parent (fs/absolutize *file*))))
(def sizes [["10KiB" 10240] ["1MiB" 1048576] ["100MiB" 104857600]])
(def defaults {:concurrency "1,4" :requests 100 :large-requests 8 :warmup 20 :large-warmup 4
               :repetitions 3 :startup-timeout 900 :timeout-seconds 300 :threshold 15.0})
(def help-text
  "Usage: ./run-benchmark.bb [--output=DIR] [--baseline=DIR] [--threshold=15]
  --concurrency=1,4      Concurrent downloads
  --requests=100         Requests/cell for 10KiB and 1MiB
  --large-requests=8     Requests/cell for 100MiB
  --warmup=20            Warmup requests/cell for small/medium files
  --large-warmup=4       Warmup requests/cell for large files
  --repetitions=3        Repetitions per protocol and concurrency
  --startup-timeout=900  Docker startup/readiness timeout in seconds
  --timeout-seconds=300  Per benchmark operation timeout
Builds and tests all modules, rebuilds Docker, benchmarks, reports and stops containers.
Volumes are retained. Results default to benchmark-results/<UTC timestamp>.
Exit codes: 0 success, 2 regression, 1 failure, 130 interruption.")

(defn fail [message] (throw (ex-info message {:safe true})))
(defn read-json [path] (json/parse-string (slurp (str path)) true))
(defn write-json! [path value] (spit (str path) (str (json/generate-string value {:pretty true}) "\n")))
(defn absolute [path] (str (fs/normalize (fs/absolutize path))))

(defn options [argv]
  (let [raw (reduce (fn [result arg]
                      (let [[name value] (str/split arg #"=" 2)
                            key (keyword (str/replace-first name #"^--" ""))]
                        (when (or (not (str/starts-with? name "--")) (nil? value)
                                  (not (contains? (assoc defaults :output nil :baseline nil) key))
                                  (contains? result key))
                          (fail (str "Unknown, duplicated or malformed option: " name)))
                        (assoc result key value))) {} argv)
        parsed (reduce-kv (fn [result key value]
                            (assoc result key (cond
                                                (= key :threshold) (Double/parseDouble value)
                                                (number? (get defaults key)) (Long/parseLong value)
                                                :else value))) defaults raw)
        workers (mapv #(Long/parseLong %) (str/split (:concurrency parsed) #"," -1))
        output (absolute (or (:output parsed) (str (fs/path repo "benchmark-results" (str/replace (str (Instant/now)) #":" "-")))))]
    (when (or (empty? workers) (not= (count workers) (count (set workers)))
              (some #(not (<= 1 % 256)) workers))
      (fail "Concurrency must contain unique values in 1..256"))
    (when-not (< 0 (:threshold parsed) 100) (fail "Threshold must be between 0 and 100"))
    (doseq [key [:requests :large-requests :warmup :large-warmup :repetitions :startup-timeout :timeout-seconds]]
      (when-not (pos? (key parsed)) (fail (str (name key) " must be positive"))))
    (when (< (apply min (map parsed [:requests :large-requests :warmup :large-warmup])) (apply max workers))
      (fail "Request and warmup counts must cover every worker"))
    (when (and (str/starts-with? output (str repo "/"))
               (some #{"target"} (str/split (subs output (inc (count repo))) #"/")))
      (fail "Output must survive Maven clean; use benchmark-results/"))
    (assoc parsed :workers workers :output output)))

(defn command!
  "Log commands without secrets. Kill descendants on timeout, failure or interruption."
  [context argv log & [opts]]
  (when (and (some-> (:cancelled? context) deref) (not (:cleanup? context)))
    (fail "Benchmark interrupted"))
  (let [task (apply process/process (merge {:dir repo :out :append :out-file (str log) :err :out} (select-keys opts [:extra-env])) argv)]
    (swap! (:processes context) conj task)
    (try
      (when (and (:timeout opts)
                 (not (.waitFor (:proc task) (long (:timeout opts)) TimeUnit/SECONDS)))
        (fail (str "Command timed out; see " (fs/file-name log))))
      (when-not (zero? (:exit @task)) (fail (str "Command failed; see " (fs/file-name log))))
      (catch Throwable error
        (process/destroy-tree task)
        (throw error))
      (finally (swap! (:processes context) disj task)))))

(defn capture! [context argv]
  (when (some-> (:cancelled? context) deref) (fail "Benchmark interrupted"))
  (let [task (apply process/process {:dir repo :out :string :err :string} argv)]
    (swap! (:processes context) conj task)
    (try
      (let [result @task]
        (when-not (zero? (:exit result)) (fail (str "Command failed: " (first argv))))
        (:out result))
      (catch Throwable error (process/destroy-tree task) (throw error))
      (finally (swap! (:processes context) disj task)))))

(defn request!
  "Only HTTP status is exposed on errors; response bodies, credentials and tickets are omitted."
  [repository method path & [body]]
  (let [response (http/request (cond-> {:uri (str (:url repository) path) :method method
                                      :basic-auth [(:user repository) (:password repository)]
                                      :headers {"Content-Type" "application/json"} :timeout 20000 :throw false}
                               body (assoc :body (json/generate-string body))))]
    (when-not (<= 200 (:status response) 299)
      (throw (ex-info (str "HTTP " (:status response)) {:safe true :http-status (:status response)})))
    (json/parse-string (:body response) true)))

(def api "/api/-default-/public/alfresco/versions/1")
(defn child! [repository parent name folder?]
  (let [path (str api "/nodes/" parent)]
    (try
      (let [entry (:entry (request! repository :get (str path "?relativePath=" (URLEncoder/encode name "UTF-8"))))]
        (when-not (= folder? (:isFolder entry)) (fail (str "Fixture name has a different node type: " name)))
        entry)
      (catch clojure.lang.ExceptionInfo error
        (if (= 404 (:http-status (ex-data error)))
          (when folder? (:entry (request! repository :post (str path "/children") {:name name :nodeType "cm:folder"})))
          (throw error))))))

(defn upload! [repository parent path]
  (let [boundary (str "protoalf-" (UUID/randomUUID))
        header (.getBytes (str "--" boundary "\r\nContent-Disposition: form-data; name=\"filedata\"; filename=\""
                               (fs/file-name path) "\"\r\nContent-Type: application/octet-stream\r\n\r\n") StandardCharsets/UTF_8)
        footer (.getBytes (str "\r\n--" boundary "--\r\n") StandardCharsets/UTF_8)]
    (with-open [body (SequenceInputStream. (Collections/enumeration
                                            [(ByteArrayInputStream. header) (io/input-stream (str path)) (ByteArrayInputStream. footer)]))]
      (let [response (http/request {:uri (str (:url repository) api "/nodes/" parent "/children") :method :post
                                   :basic-auth [(:user repository) (:password repository)] :timeout 300000 :throw false
                                   :headers {"Content-Type" (str "multipart/form-data; boundary=" boundary)} :body body})]
        (when-not (= 201 (:status response)) (fail (str "Fixture upload failed: HTTP " (:status response))))
        (:entry (json/parse-string (:body response) true))))))

(defn generate-fixture! [path label size]
  ;; java.util.Random has a specified portable algorithm. v2 avoids overwriting older fixtures.
  (let [generator (Random. (long (.hashCode (str "protoalf-benchmark-v2-" label))))
        digest (MessageDigest/getInstance "SHA-256")]
    (with-open [out (io/output-stream (str path))]
      (loop [remaining size]
        (when (pos? remaining)
          (let [data (byte-array (min remaining 1048576))]
            (.nextBytes generator data)
            (.update digest data)
            (.write out data)
            (recur (- remaining (alength data)))))))
    (.formatHex (HexFormat/of) (.digest digest))))

(defn fixtures! [repository output]
  (let [folder (child! repository "-my-" "Benchmark" true)]
    (mapv (fn [[label size]]
            (let [path (fs/path output (str "protoalf-benchmark-v2-" label ".bin"))]
              (try
                (let [hash (generate-fixture! path label size)
                      entry (or (child! repository (:id folder) (str (fs/file-name path)) false)
                                (upload! repository (:id folder) path))]
                  (when-not (= size (get-in entry [:content :sizeInBytes]))
                    (fail (str "Existing fixture size differs: " label "; it was not overwritten")))
                  {:label label :id (:id entry) :bytes size :sha256 hash})
                (finally (fs/delete-if-exists path))))) sizes)))

(defn median [values]
  (let [values (vec (sort values)) n (count values) middle (quot n 2)]
    (when (zero? n) (fail "No samples"))
    (if (odd? n) (double (values middle)) (/ (+ (values (dec middle)) (values middle)) 2.0))))

(defn summarize [report fixture repetitions workers]
  (when-not (= "completed" (:status report)) (fail (str "Incomplete benchmark: " (:label fixture))))
  (when-not (and (== (:bytes fixture) (get-in report [:corpus 0 :binary :bytes])) (= (:sha256 fixture) (get-in report [:corpus 0 :binary :sha256])))
    (fail (str "Fixture fingerprint differs: " (:label fixture) "; it was not overwritten")))
  (let [runs (:runs report)
        expected (set (for [protocol ["rest" "grpc"] concurrency workers repetition (range 1 (inc repetitions))]
                        [protocol concurrency repetition]))
        actual (mapv (fn [run] [(:protocol run) (long (:concurrency run)) (long (:repetition run))]) runs)]
    (when-not (and (= (count actual) (count expected)) (= (set actual) expected))
      (fail "Missing or duplicated benchmark cells"))
    (when (some #(or (seq (:errors %)) (not= (:successes %) (:attempts %))) runs)
      (fail "Benchmark contains failed requests"))
    (vec (for [concurrency workers protocol ["rest" "grpc"]
               :let [cells (filter #(and (== concurrency (:concurrency %)) (= protocol (:protocol %))) runs)
                     rates (map :contentMiBPerSecond cells)]]
           {:size (:label fixture) :bytes (:bytes fixture) :sha256 (:sha256 fixture) :protocol protocol :concurrency concurrency
            :throughputMiBPerSecond (median rates) :throughputMin (apply min rates) :throughputMax (apply max rates)
            :operationsPerSecond (median (map :successfulOperationsPerSecond cells))
            :p95Ms (median (map #(get-in % [:latencyMs :p95]) cells)) :attempts (reduce + (map :attempts cells))}))))

(defn compare-baseline [current baseline threshold]
  (when-not (= "completed" (:status baseline)) (fail "Baseline must be a successful complete run"))
  (when-not (= (:configuration current) (:configuration baseline)) (fail "Baseline configuration differs"))
  (let [key (juxt :size :protocol :concurrency)
        previous (into {} (map (juxt key identity) (:rows baseline)))]
    (when-not (= (set (keys previous)) (set (map key (:rows current)))) (fail "Baseline cases differ"))
    (mapv (fn [row]
            (let [old (get previous (key row))
                  drop (* 100 (- 1 (/ (:throughputMiBPerSecond row) (:throughputMiBPerSecond old))))
                  increase (* 100 (- (/ (:p95Ms row) (:p95Ms old)) 1))]
              (when-not (= (select-keys row [:bytes :sha256]) (select-keys old [:bytes :sha256]))
                (fail "Baseline corpus differs"))
              (assoc (select-keys row [:size :protocol :concurrency]) :throughputDropPercent drop :p95IncreasePercent increase
                     :regression (or (> drop threshold) (> increase threshold))))) (:rows current))))

(defn number-text [number] (String/format java.util.Locale/ROOT "%.2f" (to-array [(double number)])))

(defn write-report! [output summary]
  (write-json! (fs/path output "summary.json") summary)
  (let [rows (:rows summary)
        table (for [[size _] sizes concurrency (sort (set (map :concurrency (filter #(= size (:size %)) rows))))
                    :let [pair (into {} (map (juxt :protocol identity) (filter #(and (= size (:size %)) (== concurrency (:concurrency %))) rows)))
                          rest (get pair "rest") grpc (get pair "grpc")]
                    :when (and rest grpc)]
                (str "| " size " | " concurrency " | " (number-text (:throughputMiBPerSecond rest)) " | " (number-text (:throughputMiBPerSecond grpc))
                     " | " (number-text (/ (:throughputMiBPerSecond grpc) (:throughputMiBPerSecond rest))) " | "
                     (number-text (:p95Ms rest)) " | " (number-text (:p95Ms grpc)) " |"))
        comparisons (when (seq (:comparison summary))
                      (concat ["" "## Baseline comparison" "" (str "Threshold: " (:threshold summary) "% throughput drop or p95 increase, either protocol.") ""
                               "| File | Protocol | Concurrency | Throughput drop % | p95 increase % | Regression |"
                               "|---|---|---:|---:|---:|---|"]
                              (map #(str "| " (:size %) " | " (:protocol %) " | " (:concurrency %) " | " (number-text (:throughputDropPercent %))
                                         " | " (number-text (:p95IncreasePercent %)) " | " (if (:regression %) "YES" "no") " |") (:comparison summary))))]
    (spit (str (fs/path output "report.md"))
          (str/join "\n" (concat ["# REST/gRPC download benchmark" "" (str "Status: **" (:status summary) "**") ""
                                   (str "Client revision: `" (:gitRevision summary) "`; dirty: `" (:dirty summary) "`.") ""
                                   "Sizes: small **10 KiB**, medium **1 MiB**, large **100 MiB**." ""
                                   "Localhost, warm caches; REST HTTP, gRPC TLS. Median of repetitions; throughput is aggregate MiB/s." ""
                                   "| File | Concurrency | REST MiB/s | gRPC MiB/s | gRPC/REST | REST p95 ms | gRPC p95 ms |"
                                   "|---|---:|---:|---:|---:|---:|---:|"]
                                  table ["" (str "Successful measured requests in completed cases: " (long (reduce + 0 (map :attempts rows))) ".") ""
                                         "Raw samples, errors and per-repetition rates are in each size directory. Failed/partial cases are not improvements."
                                         "These are exploratory measurements; confirm threshold failures on an idle host."]
                                  comparisons
                                  (when (:failure summary) ["" (str "Failure: " (:failure summary) ". Inspect the phase logs and stack.log.")])
                                  ["" (str "Docker cleanup: " (get summary :cleanup "pending") ". Volumes and fixtures are retained.") ""])))))

(defn monitor! [context output stop]
  (future
    (try
      (with-open [log (io/writer (str (fs/path output "docker-stats.jsonl")))]
        (while (not @stop)
          (let [ids (str/split-lines (capture! context (into (:compose context) ["ps" "-q" "acs" "postgres" "transform-core-aio"])))]
            (when (seq ids)
              (let [text (capture! context (into ["docker" "stats" "--no-stream" "--format" "{{json .}}"] ids))]
                (doseq [line (str/split-lines text)]
                  (.write log (str (json/generate-string {:at (str (Instant/now)) :stats (json/parse-string line true)}) "\n")))
              (.flush log)))
          (Thread/sleep 2000))))
      (catch Exception _ nil))))

(defn wait-ready! [repository timeout]
  (let [deadline (+ (System/nanoTime) (* timeout 1000000000))]
    (loop []
      (let [ready? (try (request! repository :get (str api "/nodes/-my-")) true (catch Exception _ false))]
        (when-not ready?
          (when (> (System/nanoTime) deadline) (fail "ACS readiness timed out; check credentials and startup.log"))
          (Thread/sleep 2000)
          (recur))))))

(defn execute! [context args summary]
  (let [output (:output args) compose (:compose context)
        user (or (System/getenv "BENCH_USERNAME") "mcp")
        password (or (System/getenv "BENCH_PASSWORD") (System/getenv "MCP_ALFRESCO_PASSWORD") "example")
        repository {:url "http://localhost:8080/alfresco" :user user :password password}
        maven (if-let [home (System/getenv "M2_HOME")] (str (fs/path home "bin/mvn")) "mvn")]
    (command! context ["java" "-version"] (fs/path output "java-version.log"))
    (println "Building all modules and running unit tests...")
    (command! context [maven "--batch-mode" "--no-transfer-progress" "clean" "package"] (fs/path output "build.log"))
    (println "Building Docker images...")
    (command! context (into compose ["build" "acs" "mcp"]) (fs/path output "docker-build.log"))
    (swap! summary assoc :stackAttempted true)
    (println "Starting Docker environment...")
    (command! context (into compose ["up" "-d" "--force-recreate"]) (fs/path output "startup.log") {:timeout (:startup-timeout args)})
    (wait-ready! repository (:startup-timeout args))
    (let [ca (str (fs/path output "ca.crt"))]
      (command! context (into compose ["exec" "-T" "acs" "cat" "/grpc-certs/ca.crt"]) ca)
      (println "Preparing/reusing deterministic benchmark fixtures...")
      (let [corpus (fixtures! repository output)
            env {"BENCH_USERNAME" user "BENCH_PASSWORD" password "BENCH_REST_URL" (:url repository)
                 "BENCH_GRPC_HOST" "localhost" "BENCH_GRPC_PORT" "9090" "BENCH_GRPC_CA" ca "BENCH_GRPC_AUTHORITY" "localhost"}]
        (write-json! (fs/path output "fixtures.json") corpus)
        (write-json! (fs/path output "discovery.json") (request! repository :get "/api/discovery"))
        (let [ids (str/split-lines (capture! context (into compose ["ps" "-q"])))]
          (when (seq ids)
            (command! context (into ["docker" "inspect" "--format" "{{json .Id}} {{json .Image}} {{json .Config.Image}} {{json .State.StartedAt}}"] ids)
                      (fs/path output "containers.log"))))
        (let [stop (atom false) monitor (monitor! context output stop)]
          (try
            (doseq [fixture corpus]
              (let [label (:label fixture) large? (= label "100MiB")]
                (println (str "Benchmarking " label "..."))
                (command! context [(str (fs/path repo "protoalf-client/src/test/benchmark/run.sh")) (str "--nodes=" (:id fixture))
                                   "--scenarios=download" (str "--concurrency=" (:concurrency args))
                                   (str "--requests=" (if large? (:large-requests args) (:requests args)))
                                   (str "--warmup=" (if large? (:large-warmup args) (:warmup args)))
                                   (str "--repetitions=" (:repetitions args)) (str "--timeout-seconds=" (:timeout-seconds args))
                                   (str "--label=" label "-warm-localhost") (str "--output=" (fs/path output label))]
                          (fs/path output (str label ".log")) {:extra-env env})
                (swap! summary update :rows into (summarize (read-json (fs/path output label "report.json")) fixture (:repetitions args) (:workers args)))
                (write-report! output @summary)))
            (finally (reset! stop true) (deref monitor 20000 nil) (future-cancel monitor))))))))

(defn finish!
  "Shared by normal completion and the shutdown hook; teardown runs at most once."
  [context args summary closing? interrupted?]
  (when (compare-and-set! closing? false true)
    (when interrupted?
      (when-let [cancelled? (:cancelled? context)] (reset! cancelled? true))
      (swap! summary assoc :status "interrupted")
      (doseq [task @(:processes context)] (process/destroy-tree task)))
    (if (:stackAttempted @summary)
      (do
        (println "Stopping Docker environment (retaining volumes)...")
        (try (command! (assoc context :cleanup? true) (into (:compose context) ["logs" "--no-color" "--tail" "200"]) (fs/path (:output args) "stack.log") {:timeout 30})
             (catch Exception _ nil))
        (try
          (command! (assoc context :cleanup? true) (into (:compose context) ["down" "--timeout" "30"]) (fs/path (:output args) "shutdown.log") {:timeout 120})
          (swap! summary assoc :cleanup "stopped")
          (catch Exception _ (swap! summary assoc :cleanup "FAILED; stop Docker manually" :status "cleanup_failed"))))
      (swap! summary assoc :cleanup "not started"))
    (swap! summary assoc :finished (str (Instant/now)))
    (write-report! (:output args) @summary)))

(defn run! [args]
  (let [context {:compose ["docker" "compose" "-f" (str (fs/path repo "docker/docker-compose.yml"))]
                 :processes (atom #{}) :cancelled? (atom false)}
        baseline (when (:baseline args) (read-json (fs/path (:baseline args) "summary.json")))
        configuration {:requests (:requests args) :largeRequests (:large-requests args) :warmup (:warmup args) :largeWarmup (:large-warmup args)
                       :repetitions (:repetitions args) :concurrency (:workers args) :timeoutSeconds (:timeout-seconds args)
                       :transport "REST HTTP / gRPC TLS" :cache "warm" :corpusVersion 2 :user (or (System/getenv "BENCH_USERNAME") "mcp")}
        summary (atom {:status "running" :rows [] :configuration configuration :started (str (Instant/now)) :threshold (:threshold args)})
        closing? (atom false)
        hook (Thread. #(finish! context args summary closing? true))]
    (doseq [tool ["docker" "java" "mvn"]] (when-not (fs/which tool) (fail (str "Required command not found: " tool))))
    (when (and baseline (or (not= "completed" (:status baseline)) (not= configuration (:configuration baseline))))
      (fail "Baseline must be complete and use identical parameters, corpus version and user"))
    (when (fs/exists? (:output args)) (fail "Output directory already exists"))
    (fs/create-dirs (:output args))
    (capture! context ["docker" "compose" "version"])
    (swap! summary assoc :gitRevision (str/trim (capture! context ["git" "rev-parse" "HEAD"]))
           :dirty (not (str/blank? (capture! context ["git" "status" "--porcelain"]))))
    (.addShutdownHook (Runtime/getRuntime) hook)
    (try
      (execute! context args summary)
      (swap! summary assoc :status "completed")
      (when baseline
        (let [changes (compare-baseline @summary baseline (:threshold args))]
          (swap! summary assoc :comparison changes :baseline (absolute (:baseline args)))
          (when (some :regression changes) (swap! summary assoc :status "regression"))))
      (catch Throwable error
        (when-not @closing?
          (swap! summary assoc :status "failed" :failure (if (:safe (ex-data error)) (ex-message error) (str (type error))))))
      (finally
        (finish! context args summary closing? false)
        (try (.removeShutdownHook (Runtime/getRuntime) hook)
             (catch IllegalStateException _ nil))))
    (println (slurp (str (fs/path (:output args) "report.md"))))
    (println "Results:" (:output args))
    (case (:status @summary) "completed" 0 "regression" 2 "interrupted" 130 1)))

(defn -main [& argv]
  (if (some #{"--help" "-h"} argv)
    (println help-text)
    (let [code (try (run! (options argv))
                    (catch Exception error
                      (binding [*out* *err*] (println (if (:safe (ex-data error)) (ex-message error) "Benchmark failed; inspect logs and arguments")))
                      1))]
      (when-not (zero? code) (System/exit code)))))

(when-let [script (System/getProperty "babashka.file")]
  (when (= (fs/normalize (fs/absolutize script)) (fs/normalize (fs/absolutize *file*)))
    (apply -main *command-line-args*)))
