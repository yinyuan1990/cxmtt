#!/bin/bash
###############################################
# 停止 chexuan-mtt 容器（主服 app / mysql / redis / nginx 不受影响）
#
# 用法：
#   cd /home/fzcx/chexuan
#   bash mtt-stop.sh
###############################################
set -euo pipefail
cd "$(dirname "$0")"

echo "========================================"
echo "  停止 chexuan-mtt"
echo "========================================"

docker compose stop mtt
docker compose ps mtt || true

echo ""
echo "✅ chexuan-mtt 已停止"
echo "   ⚠️  比赛桌上还没结束的比赛会中断，重启前请确认没有进行中的比赛（后台 MTT 管理页查询「进行中」场次）"
