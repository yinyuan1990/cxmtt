# ============================================================
# 重启 chexuan-mtt 容器（不重新上传 jar、不重建镜像）
# 改了代码请用 update.ps1
# 用法：.\deploy\restart.ps1
# ============================================================
$ErrorActionPreference = "Stop"
. "$PSScriptRoot\common.ps1"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  重启 chexuan-mtt" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

Invoke-RemoteCompose "restart $ComposeService"
Start-Sleep -Seconds 5
Invoke-RemoteCompose "ps $ComposeService"

Write-Host "`n📝 最近日志：" -ForegroundColor Cyan
Invoke-RemoteCompose "logs --tail 20 $ComposeService"

Write-Host "`n✅ chexuan-mtt 已重启" -ForegroundColor Green
