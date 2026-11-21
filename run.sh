#!/bin/sh
# Builds the two modules and runs the whole stack (Alfresco + gRPC module + dependencies + MCP server) in Docker.

cd "$(dirname "$0")" || exit 1
export COMPOSE_FILE_PATH="${PWD}/docker/docker-compose.yml"

if [ -z "${M2_HOME}" ]; then
  export MVN_EXEC="mvn"
else
  export MVN_EXEC="${M2_HOME}/bin/mvn"
fi

start() {
    docker compose -f "$COMPOSE_FILE_PATH" up --build -d
}

down() {
    docker compose -f "$COMPOSE_FILE_PATH" down
}

purge() {
    # Removes containers AND volumes: repository data, database, index and gRPC certificates
    docker compose -f "$COMPOSE_FILE_PATH" down -v
}

build() {
    $MVN_EXEC clean package
}

tail() {
    docker compose -f "$COMPOSE_FILE_PATH" logs -f
}

tail_all() {
    docker compose -f "$COMPOSE_FILE_PATH" logs --tail="all"
}

reload_acs() {
    docker compose -f "$COMPOSE_FILE_PATH" stop acs
    $MVN_EXEC clean package -pl protoalf-platform
    docker compose -f "$COMPOSE_FILE_PATH" up --build -d acs
}

reload_mcp() {
    $MVN_EXEC clean package -pl protoalf-mcp -am
    docker compose -f "$COMPOSE_FILE_PATH" up --build -d mcp
}

case "$1" in
  build_start)
    down
    build
    start
    tail
    ;;
  start)
    start
    tail
    ;;
  stop)
    down
    ;;
  purge)
    purge
    ;;
  tail)
    tail
    ;;
  tail_all)
    tail_all
    ;;
  reload_acs)
    reload_acs
    tail
    ;;
  reload_mcp)
    reload_mcp
    tail
    ;;
  build)
    build
    ;;
  *)
    echo "Usage: $0 {build_start|start|stop|purge|tail|tail_all|reload_acs|reload_mcp|build}"
esac
