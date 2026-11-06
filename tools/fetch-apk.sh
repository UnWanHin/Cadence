#!/usr/bin/env bash
# Download the released APK into dist/ so ./start.sh can serve /app.apk.
#
#   ./tools/fetch-apk.sh            newest release that has an APK asset
#   ./tools/fetch-apk.sh v0.3.3     a specific tag
#
# dist/ is gitignored, so a fresh clone has no APK until this runs once.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO="${REPO:-UnWanHin/Cadence}"
TAG="${1:-}"

command -v curl >/dev/null 2>&1 || { echo "找不到 curl。" >&2; exit 1; }
command -v node >/dev/null 2>&1 || { echo "找不到 node。" >&2; exit 1; }

api="https://api.github.com/repos/$REPO/releases"
if [ -n "$TAG" ]; then
    url="$api/tags/$TAG"
else
    url="$api/latest"
fi

json="$(curl -fsSL --max-time 30 "$url")" || {
    echo "拉取 release 信息失败（$url）。检查网络或 gh auth。" >&2
    exit 1
}

# Pick the first .apk asset download URL.
asset="$(printf '%s' "$json" | node -e '
let raw = "";
process.stdin.on("data", (chunk) => { raw += chunk; });
process.stdin.on("end", () => {
  const release = JSON.parse(raw);
  const apk = (release.assets || []).find((a) => a.name.endsWith(".apk"));
  if (apk) process.stdout.write(apk.browser_download_url);
});')"

if [ -z "$asset" ]; then
    echo "这个 release 里没有 .apk 资源。" >&2
    exit 1
fi

mkdir -p "$ROOT/dist"
target="$ROOT/dist/cadence.apk"
echo "下载 $asset"
curl -fL --max-time 300 --progress-bar -o "$target.part" "$asset"
mv "$target.part" "$target"
echo "已保存 $target（$(du -h "$target" | cut -f1)）"
node -e '
const fs = require("fs");
const path = process.argv[1];
const zip = fs.readFileSync(path);
const entries = zip.includes(Buffer.from("AndroidManifest.xml"));
console.log(entries ? "  APK 结构看起来正常" : "  警告：这个文件不像 APK");
' "$target"
echo "现在 ./start.sh 会把它作为 /app.apk 提供下载。"
