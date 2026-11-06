#!/usr/bin/env bash
# Starts the HTML room on port 8080 (default).
#
#   ./start.sh              run in the foreground on 8080
#   ./start.sh --bg         run in the background, log to .run/server.log
#   ./start.sh --stop       stop the background instance
#   ./start.sh --status     show whether it is running
#   ./start.sh --port 9000  use another port
#
# The page and /api/v1 live on the same origin, so a phone only needs the URL.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUN_DIR="$ROOT/.run"
PID_FILE="$RUN_DIR/server.pid"
LOG_FILE="$RUN_DIR/server.log"

PORT="${PORT:-8080}"
MODE="foreground"
DATA_DIR="${DATA_DIR:-}"

usage() {
    sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
}

while [ $# -gt 0 ]; do
    case "$1" in
        --port|-p) PORT="${2:?需要端口号}"; shift 2 ;;
        --data|-d) DATA_DIR="${2:?需要目录}"; shift 2 ;;
        --bg) MODE="background"; shift ;;
        --stop) MODE="stop"; shift ;;
        --status) MODE="status"; shift ;;
        --help|-h) usage ;;
        *) echo "未知参数：$1（用 --help 看用法）" >&2; exit 2 ;;
    esac
done

require_node() {
    if ! command -v node >/dev/null 2>&1; then
        echo "找不到 node。先装 Node.js 18 或更新版本。" >&2
        exit 1
    fi
    local major
    major="$(node -p 'process.versions.node.split(".")[0]')"
    if [ "$major" -lt 18 ]; then
        echo "Node.js 版本太旧（$(node -v)），至少需要 18。" >&2
        exit 1
    fi
}

running_pid() {
    [ -f "$PID_FILE" ] || return 1
    local pid
    pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    [ -n "$pid" ] || return 1
    # Guard against a stale pid that now belongs to something else.
    if kill -0 "$pid" 2>/dev/null; then
        echo "$pid"
        return 0
    fi
    rm -f "$PID_FILE"
    return 1
}

lan_addresses() {
    # Every non-loopback IPv4, best candidates first.
    ip -4 -o addr show scope global 2>/dev/null \
        | awk '{print $2, $4}' \
        | sed 's|/.*||' \
        | grep -vE '^(tun|ppp|tailscale|docker|br-|veth|virbr)' \
        | awk '{print $2}' \
        | grep -E '^(192\.168\.|10\.|172\.(1[6-9]|2[0-9]|3[01])\.)' \
        | sort -u
}

case "$MODE" in
    stop)
        if pid="$(running_pid)"; then
            kill "$pid" 2>/dev/null || true
            for _ in $(seq 1 20); do
                kill -0 "$pid" 2>/dev/null || break
                sleep 0.1
            done
            kill -9 "$pid" 2>/dev/null || true
            rm -f "$PID_FILE"
            echo "已停止（pid $pid）"
        else
            echo "没有在运行的后台实例"
        fi
        exit 0
        ;;
    status)
        if pid="$(running_pid)"; then
            echo "正在运行（pid $pid）"
            for address in $(lan_addresses); do
                echo "  http://$address:$PORT/"
            done
        else
            echo "没有在运行"
        fi
        exit 0
        ;;
esac

require_node
mkdir -p "$RUN_DIR"

if pid="$(running_pid)"; then
    echo "已经有一个实例在跑（pid $pid）。先 ./start.sh --stop 再启动。" >&2
    exit 1
fi

if [ -z "$DATA_DIR" ]; then
    DATA_DIR="$ROOT/database/local-server/data"
fi

export PORT
export DATA_DIR
# A phone-hosted room and a laptop-hosted room must not fight over 8788.
export DISCOVERY_PORT="${DISCOVERY_PORT:-8788}"
export ROOM_NAME="${ROOM_NAME:-电脑的房间}"

# Both modes start the server the same way; only what we do afterwards differs.
if [ "$MODE" = "background" ]; then
    nohup node "$ROOT/database/local-server/server.mjs" >"$LOG_FILE" 2>&1 &
else
    node "$ROOT/database/local-server/server.mjs" &
fi
SERVER_PID=$!
echo "$SERVER_PID" >"$PID_FILE"

cleanup() {
    rm -f "$PID_FILE"
    kill "$SERVER_PID" 2>/dev/null || true
}
if [ "$MODE" = "foreground" ]; then
    trap cleanup EXIT INT TERM
fi

# Wait for the health endpoint instead of assuming a fixed delay.
health_ok=""
for _ in $(seq 1 60); do
    if curl -s --noproxy '*' --max-time 1 "http://127.0.0.1:$PORT/api/v1/health" >/dev/null 2>&1; then
        health_ok="yes"
        break
    fi
    if ! kill -0 "$SERVER_PID" 2>/dev/null; then
        echo "进程已退出。"
        [ "$MODE" = "background" ] && tail -20 "$LOG_FILE"
        rm -f "$PID_FILE"
        exit 1
    fi
    sleep 0.25
done

cat <<EOF

  校园智能运动平台 · HTML 房间
  ─────────────────────────────
  本机页面:   http://127.0.0.1:$PORT/
EOF

addresses="$(lan_addresses || true)"
if [ -n "$addresses" ]; then
    while IFS= read -r address; do
        [ -n "$address" ] && echo "  局域网页面: http://$address:$PORT/"
    done <<<"$addresses"
else
    echo "  局域网页面: 未检测到局域网地址（连上 Wi-Fi 或热点后再看）"
fi

cat <<EOF

  接口前缀:   /api/v1
  房间数据:   $DATA_DIR
  安装包下载: http://<上面的地址>:$PORT/app.apk

  手机用浏览器打开「局域网页面」即可加入同一个房间，
  页面和接口同源，不需要再拼 ?api= 参数。

EOF

if [ ! -f "$ROOT/dist/cadence.apk" ]; then
    echo "  提示：dist/cadence.apk 不存在，/app.apk 会返回 404。"
    echo "        需要发安装包时先跑一次 ./tools/fetch-apk.sh"
    echo
fi

if [ -z "$health_ok" ]; then
    echo "  警告：健康检查没有通过，服务可能还没起来。" >&2
fi

if [ "$MODE" = "background" ]; then
    echo "  已在后台运行，日志：$LOG_FILE"
    echo "  停止：./start.sh --stop"
    echo
fi

if [ "$MODE" = "foreground" ]; then
    echo "  按 Ctrl-C 停止。"
    echo
    wait "$SERVER_PID"
fi
