#!/usr/bin/env bash
# Runs the LLM proxy in the background, without admin rights.
#
# Usage: bin/llm-proxy.sh {start|stop|restart|status|logs}
#        bin/llm-proxy.sh probe <upstream> <model> [--min 4000] [--max 131072]
#
# "probe" measures the context an upstream really handles (runs in the foreground).
#
# Everything lives in the project folder: run/llm-proxy.pid and logs/llm-proxy.log.
# Variables from .env (project root) are loaded when the file exists.
# Overridable with environment variables:
#   JAVA              java executable (default: $JAVA_HOME/bin/java, then java)
#   JAVA_OPTS         extra JVM options, e.g. "-Dhttps.proxyHost=proxy.corp -Dhttps.proxyPort=8080"
#   LLM_PROXY_JAR     jar to run (default: target/llm-proxy.jar)
#   LLM_PROXY_CONFIG  configuration file (default: config.json)
#   LLM_PROXY_PORT    port used by "status" for the health check (default: 4000)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    . ./.env
    set +a
fi

if [[ -n "${JAVA:-}" ]]; then
    :
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
    JAVA="$JAVA_HOME/bin/java"
else
    JAVA="java"
fi
JAR="${LLM_PROXY_JAR:-target/llm-proxy.jar}"
CONFIG="${LLM_PROXY_CONFIG:-config.json}"
PORT="${LLM_PROXY_PORT:-4000}"
PID_FILE="run/llm-proxy.pid"
LOG_FILE="logs/llm-proxy.log"

running_pid() {
    [[ -f "$PID_FILE" ]] || return 1
    local pid
    pid="$(cat "$PID_FILE")"
    if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
        echo "$pid"
        return 0
    fi
    rm -f "$PID_FILE"
    return 1
}

start() {
    local pid
    if pid="$(running_pid)"; then
        echo "LLM proxy already running (PID $pid)."
        return 0
    fi
    [[ -f "$JAR" ]] || { echo "Jar not found: $JAR (run: mvn clean package)" >&2; exit 1; }
    [[ -f "$CONFIG" ]] || { echo "Configuration not found: $CONFIG (copy config.example.json)" >&2; exit 1; }
    mkdir -p run logs
    # shellcheck disable=SC2086
    nohup "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" "$CONFIG" >>"$LOG_FILE" 2>&1 </dev/null &
    echo $! >"$PID_FILE"
    sleep 2
    if pid="$(running_pid)"; then
        echo "LLM proxy started (PID $pid), logs: $LOG_FILE"
    else
        echo "LLM proxy failed to start, last log lines:" >&2
        tail -n 20 "$LOG_FILE" >&2
        exit 1
    fi
}

stop() {
    local pid
    if ! pid="$(running_pid)"; then
        echo "LLM proxy is not running."
        return 0
    fi
    kill -TERM "$pid"
    # The proxy lets in-flight requests finish (timeouts.shutdown, 10s by default).
    for _ in $(seq 1 30); do
        kill -0 "$pid" 2>/dev/null || { rm -f "$PID_FILE"; echo "LLM proxy stopped."; return 0; }
        sleep 0.5
    done
    echo "Still running after 15s, killing it."
    kill -KILL "$pid" 2>/dev/null || true
    rm -f "$PID_FILE"
}

status() {
    local pid
    if pid="$(running_pid)"; then
        echo "LLM proxy running (PID $pid)."
        if command -v curl >/dev/null 2>&1; then
            curl -fsS "http://127.0.0.1:$PORT/health" && echo || echo "Health check on port $PORT failed."
        fi
    else
        echo "LLM proxy is not running."
        return 3
    fi
}

case "${1:-}" in
    start) start ;;
    stop) stop ;;
    restart) stop; start ;;
    status) status ;;
    logs) mkdir -p logs; touch "$LOG_FILE"; tail -n 50 -f "$LOG_FILE" ;;
    probe)
        shift
        [[ -f "$JAR" ]] || { echo "Jar not found: $JAR (run: mvn clean package)" >&2; exit 1; }
        # shellcheck disable=SC2086
        exec "$JAVA" ${JAVA_OPTS:-} -jar "$JAR" probe --config "$CONFIG" "$@" ;;
    *) echo "Usage: $0 {start|stop|restart|status|logs|probe <upstream> <model>}" >&2; exit 2 ;;
esac
