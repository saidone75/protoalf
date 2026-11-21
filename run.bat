@ECHO OFF
REM Builds the two modules and runs the whole stack (Alfresco + gRPC module + dependencies + MCP server) in Docker.

CD /D "%~dp0"
SET COMPOSE_FILE_PATH=%CD%\docker\docker-compose.yml

IF [%M2_HOME%]==[] (
    SET MVN_EXEC=mvn
)

IF NOT [%M2_HOME%]==[] (
    SET MVN_EXEC=%M2_HOME%\bin\mvn
)

IF [%1]==[] (
    echo "Usage: %0 {build_start|start|stop|purge|tail|tail_all|reload_acs|reload_mcp|build}"
    GOTO END
)

IF %1==build_start (
    CALL :down
    CALL :build
    CALL :start
    CALL :tail
    GOTO END
)
IF %1==start (
    CALL :start
    CALL :tail
    GOTO END
)
IF %1==stop (
    CALL :down
    GOTO END
)
IF %1==purge (
    CALL :purge
    GOTO END
)
IF %1==tail (
    CALL :tail
    GOTO END
)
IF %1==tail_all (
    CALL :tail_all
    GOTO END
)
IF %1==reload_acs (
    CALL :reload_acs
    CALL :tail
    GOTO END
)
IF %1==reload_mcp (
    CALL :reload_mcp
    CALL :tail
    GOTO END
)
IF %1==build (
    CALL :build
    GOTO END
)
echo "Usage: %0 {build_start|start|stop|purge|tail|tail_all|reload_acs|reload_mcp|build}"
:END
EXIT /B %ERRORLEVEL%

:start
    docker compose -f "%COMPOSE_FILE_PATH%" up --build -d
EXIT /B 0
:down
    docker compose -f "%COMPOSE_FILE_PATH%" down
EXIT /B 0
:purge
    REM Removes containers AND volumes: repository data, database, index and gRPC certificates
    docker compose -f "%COMPOSE_FILE_PATH%" down -v
EXIT /B 0
:build
    call %MVN_EXEC% clean package
EXIT /B 0
:tail
    docker compose -f "%COMPOSE_FILE_PATH%" logs -f
EXIT /B 0
:tail_all
    docker compose -f "%COMPOSE_FILE_PATH%" logs --tail="all"
EXIT /B 0
:reload_acs
    docker compose -f "%COMPOSE_FILE_PATH%" stop acs
    call %MVN_EXEC% clean package -pl protoalf-platform
    docker compose -f "%COMPOSE_FILE_PATH%" up --build -d acs
EXIT /B 0
:reload_mcp
    call %MVN_EXEC% clean package -pl protoalf-mcp -am
    docker compose -f "%COMPOSE_FILE_PATH%" up --build -d mcp
EXIT /B 0
