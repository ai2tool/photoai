#!/usr/bin/env bash
# 发版辅助：为新版本生成 update/app-version.json。
#
# 用法:
#   ./scripts/publish-update.sh <versionName> <versionCode> <apkUrl> [apkFile]
#
# 示例（本地打好包后把 APK 传到 GitHub Release）:
#   ./scripts/publish-update.sh 1.2 100 https://github.com/ai2tool/photoai/releases/download/v1.2/photoai-1.2.apk \
#       app/build/outputs/apk/release/app-release.apk
#
# 生成后把 update/app-version.json 提交并推送到 main 分支，客户端即可收到更新。
set -euo pipefail

VER="${1:?用法: publish-update.sh <versionName> <versionCode> <apkUrl> [apkFile]}"
CODE="${2:?缺少 versionCode}"
URL="${3:?缺少 apkUrl}"
APK="${4:-}"

cd "$(dirname "$0")/.."

if [ -n "$APK" ]; then
  [ -f "$APK" ] || { echo "APK 不存在: $APK"; exit 1; }
  SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")
  SHA=$(shasum -a 256 "$APK" | awk '{print $1}')
else
  SIZE=0
  SHA=""
  echo "⚠️  未提供 APK 文件，sha256/size 留空（客户端将跳过大小与校验和检查，不建议）"
fi

cat > update/app-version.json <<EOF
{
  "versionCode": $CODE,
  "versionName": "$VER",
  "url": "$URL",
  "sha256": "$SHA",
  "size": $SIZE,
  "notes": "修复已知问题，优化使用体验。",
  "force": false
}
EOF

echo "✅ 已生成 update/app-version.json"
echo "   记得把 notes 改成这次的更新说明，然后提交推送："
echo "   git add update/app-version.json && git commit -m 'Release v$VER' && git push"