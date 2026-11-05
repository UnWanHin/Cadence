#!/usr/bin/env bash
# Prints the URL the room service is reachable at right now.
#
# The machine moves between networks (campus Wi-Fi, phone hotspot), so the LAN
# address changes while the server keeps listening on 0.0.0.0. Run this after
# switching networks instead of guessing the IP.
set -euo pipefail

PORT="${1:-8787}"

if ! ss -ltn 2>/dev/null | grep -q ":${PORT} "; then
  echo "端口 ${PORT} 没有在监听。先启动服务：" >&2
  echo "  cd $(cd "$(dirname "$0")/../.." && pwd) && nohup node database/local-server/server.mjs > /tmp/cadence-server.log 2>&1 &" >&2
  exit 1
fi

# Skip loopback, the Clash/FlClash tun interface, and anything non-RFC1918.
addrs=$(ip -4 -o addr show scope global 2>/dev/null \
  | awk '{print $2, $4}' \
  | grep -vE '^(FlClash|tun|utun|docker|br-|veth|virbr)' \
  | awk '{print $2}' \
  | cut -d/ -f1 \
  | grep -E '^(10\.|192\.168\.|172\.(1[6-9]|2[0-9]|3[01])\.)' || true)

if [ -z "$addrs" ]; then
  echo "没有找到局域网地址（是不是没连网？）" >&2
  exit 1
fi

echo "房间服务已监听 0.0.0.0:${PORT}"
echo
while read -r ip; do
  [ -z "$ip" ] && continue
  state=$(curl -s --noproxy '*' --max-time 2 -o /dev/null -w '%{http_code}' "http://${ip}:${PORT}/api/v1/health" || echo 000)
  if [ "$state" = "200" ]; then
    echo "  http://${ip}:${PORT}/          ← 手机/浏览器用这个"
  else
    echo "  http://${ip}:${PORT}/          （本机探测返回 ${state}，可能被代理或防火墙拦）"
  fi
done <<< "$addrs"

echo
echo "APK 里「加入房间」的地址（不带 http://）："
while read -r ip; do
  [ -z "$ip" ] && continue
  echo "  ${ip}:${PORT}"
done <<< "$addrs"
