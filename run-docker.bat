@echo off
setlocal EnableExtensions
set "SCRIPT_NAME=%~nx0"

rem Resolve build and Compose paths even when invoked from another directory.
pushd "%~dp0" || exit /b 1

set "COMMAND="
for %%C in (build build_start start stop restart update purge tail tail_all status reload_acs reload_mcp) do (
    if /i "%~1"=="%%C" set "COMMAND=%%C"
)
if /i "%~1"=="-h" goto :help
if /i "%~1"=="--help" goto :help
if /i "%~1"=="help" goto :help
if not defined COMMAND goto :usage_error
if not "%~2"=="" goto :usage_error

docker compose version >nul 2>&1
if not errorlevel 1 (
    set "COMPOSE=docker compose"
) else (
    docker-compose version >nul 2>&1
    if errorlevel 1 (
        echo ERROR: Docker Compose is unavailable. Install the Docker Compose plugin. 1>&2
        popd & exit /b 1
    )
    set "COMPOSE=docker-compose"
)
set "COMPOSE=%COMPOSE% -f "%CD%\docker\docker-compose.yml""

if defined M2_HOME (
    set MVN_EXEC="%M2_HOME%\bin\mvn.cmd"
) else (
    rem Never quote a PATH lookup: cmd then breaks %~dp0 inside mvn.cmd.
    set "MVN_EXEC=mvn"
)

goto :cmd_%COMMAND%

:cmd_build
call :build || goto :fail
goto :done

:cmd_build_start
:cmd_update
call :build || goto :fail
call :start || goto :fail
call :tail_logs
goto :done

:cmd_start
call :start || goto :fail
call :tail_logs
goto :done

:cmd_stop
%COMPOSE% down || goto :fail
goto :done

:cmd_restart
%COMPOSE% down || goto :fail
call :start || goto :fail
call :tail_logs
goto :done

:cmd_purge
echo Removing stack containers and volumes (repository, database, indexes and TLS certificates).
%COMPOSE% down -v --remove-orphans || goto :fail
goto :done

:cmd_tail
call :tail_logs
goto :done

:cmd_tail_all
%COMPOSE% logs --tail=all || goto :fail
goto :done

:cmd_status
%COMPOSE% ps -a || goto :fail
goto :done

:cmd_reload_acs
call %MVN_EXEC% clean package -pl protoalf-platform -am || goto :fail
%COMPOSE% up --build -d acs || goto :fail
call :tail_logs
goto :done

:cmd_reload_mcp
call %MVN_EXEC% clean package -pl protoalf-mcp -am || goto :fail
%COMPOSE% up --build -d mcp || goto :fail
call :tail_logs
goto :done

rem ---------------------------------------------------------------------------

:build
rem Include the shared client and exporter; examples are not part of the stack.
call %MVN_EXEC% clean package -pl protoalf-platform,protoalf-mcp -am || exit /b 1
%COMPOSE% build acs mcp || exit /b 1
exit /b 0

:start
%COMPOSE% up --build -d || exit /b 1
exit /b 0

:tail_logs
%COMPOSE% logs --tail=200 -f
exit /b %ERRORLEVEL%

:help
call :usage
popd & exit /b 0

:usage_error
call :usage 1>&2
popd & exit /b 1

:usage
echo Usage: %SCRIPT_NAME% {build^|build_start^|start^|stop^|restart^|update^|purge^|tail^|tail_all^|status^|reload_acs^|reload_mcp}
exit /b 0

:fail
popd & exit /b 1

:done
popd & exit /b 0
