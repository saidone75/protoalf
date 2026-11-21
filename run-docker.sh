#!/usr/bin/env bash
set -euo pipefail

# Resolve build and Compose paths even when invoked from another directory.
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"

usage() {
    echo "Usage: ${0##*/} {build|build_start|start|stop|restart|update|purge|tail|tail_all|status|reload_acs|reload_mcp}"
}

COMMAND=${1:-}
COMMAND=${COMMAND,,}
case "$COMMAND" in
    -h|--help|help) usage; exit 0 ;;
    build|build_start|start|stop|restart|update|purge|tail|tail_all|status|reload_acs|reload_mcp) ;;
    *) usage >&2; exit 1 ;;
esac
if (( $# != 1 )); then
    usage >&2
    exit 1
fi

COMPOSE_CMD=()
if docker compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker compose)
elif command -v docker-compose >/dev/null 2>&1 && docker-compose version >/dev/null 2>&1; then
    COMPOSE_CMD=(docker-compose)
else
    echo "ERROR: Docker Compose is unavailable. Install the Docker Compose plugin." >&2
    exit 1
fi
COMPOSE_CMD+=(-f "$PWD/docker/docker-compose.yml")

MVN_EXEC=${M2_HOME:+${M2_HOME}/bin/}mvn

build() {
    # Include the shared client; the standalone example is not part of the stack.
    "$MVN_EXEC" clean package -pl protoalf-platform,protoalf-mcp -am
    "${COMPOSE_CMD[@]}" build acs mcp
}

start() {
    "${COMPOSE_CMD[@]}" up --build -d
}

tail_logs() {
    "${COMPOSE_CMD[@]}" logs --tail=200 -f
}

case "$COMMAND" in
    build) build ;;
    build_start|update) build; start; tail_logs ;;
    start) start; tail_logs ;;
    stop) "${COMPOSE_CMD[@]}" down ;;
    restart) "${COMPOSE_CMD[@]}" down; start; tail_logs ;;
    purge)
        echo "Removing stack containers and volumes (repository, database, indexes and TLS certificates)."
        "${COMPOSE_CMD[@]}" down -v --remove-orphans
        ;;
    tail) tail_logs ;;
    tail_all) "${COMPOSE_CMD[@]}" logs --tail=all ;;
    status) "${COMPOSE_CMD[@]}" ps -a ;;
    reload_acs)
        "$MVN_EXEC" clean package -pl protoalf-platform -am
        "${COMPOSE_CMD[@]}" up --build -d acs
        tail_logs
        ;;
    reload_mcp)
        "$MVN_EXEC" clean package -pl protoalf-mcp -am
        "${COMPOSE_CMD[@]}" up --build -d mcp
        tail_logs
        ;;
esac
