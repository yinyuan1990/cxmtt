# ============================================================
# 停止 chexuan-mtt 容器（mysql/redis/nginx/app 主服不受影响）
# 用法：.\deploy\stop.ps1
# ============================================================
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\common.ps1"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  停止 chexuan-mtt" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Invoke-RemoteCompose "stop $ComposeService"

try { Invoke-RemoteCompose "ps $ComposeService" } catch { }

Write-Host "`n✅ chexuan-mtt 已停止" -ForegroundColor Green
Write-Host "   ⚠️  比赛桌上还没结束的比赛会中断，重启前请确认没有进行中的比赛（后台/mtt/list 查询 status=2）" -ForegroundColor Yellow
