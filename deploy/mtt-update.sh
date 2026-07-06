#!/bin/bash
###############################################
# 更新 chexuan-mtt（服务器上直接运行，不需要本地上传脚本）
#
# 用法（先把新 jar 传到本目录覆盖 chexuan-mtt.jar，再执行）：
#   cd /home/fzcx/chexuan
#   bash mtt-update.sh
###############################################
set -euo pipefail
cd "$(dirname "$0")"

if [[ ! -f chexuan-mtt.jar ]]; then
  echo "❌ 找不到 chexuan-mtt.jar，请先上传到 $(pwd)/chexuan-mtt.jar"
  exit 1
fi

echo "========================================"
echo "  更新 chexuan-mtt"
echo "========================================"

echo "🔨 重建镜像..."
docker compose build mtt

echo "🚀 重启容器..."
docker compose up -d mtt

echo "⏳ 等待 5 秒..."
sleep 5

echo ""
echo "📊 容器状态："
docker compose ps mtt

echo ""
echo "📝 最近日志："
docker compose logs --tail 30 mtt

echo ""
echo "✅ chexuan-mtt 更新完成"
