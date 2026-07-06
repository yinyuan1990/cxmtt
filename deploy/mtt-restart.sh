#!/bin/bash
###############################################
# 重启 chexuan-mtt 容器（不重建镜像，代码/jar 没变时用这个）
# 改了 jar 请用 mtt-update.sh
#
# 用法：
#   cd /home/fzcx/chexuan
#   bash mtt-restart.sh
###############################################
set -euo pipefail
cd "$(dirname "$0")"

echo "========================================"
echo "  重启 chexuan-mtt"
echo "========================================"

docker compose restart mtt
sleep 3

echo ""
echo "📊 容器状态："
docker compose ps mtt

echo ""
echo "📝 最近日志："
docker compose logs --tail 20 mtt

echo ""
echo "✅ chexuan-mtt 已重启"
