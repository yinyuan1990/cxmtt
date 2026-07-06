# ============================================================
# 更新 chexuan-mtt：本地打包(可跳过) → 上传 jar → 重建镜像 → 重启容器
# 用法（在仓库根目录 e:\cocos\chexuan-mtt 下）：
#   .\deploy\update.ps1                # mvn package 后上传
#   .\deploy\update.ps1 -SkipBuild      # 跳过本地打包，直接用已有 target\chexuan-mtt.jar
#   .\deploy\update.ps1 -JarPath xxx.jar
# ============================================================
param(
    [switch]$SkipBuild,
    [string]$JarPath
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\common.ps1"

if (-not $JarPath) { $JarPath = Join-Path $RepoRoot $LocalJarPath }

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "  更新 chexuan-mtt (Docker)" -ForegroundColor Cyan
Write-Host "  服务器: $ServerUser@${ServerHost}:$ServerPort" -ForegroundColor Cyan
Write-Host "  目录:   $RemoteDir" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan

if (-not $SkipBuild) {
    Write-Host "`n🔨 本地打包 mvn clean package -DskipTests ..." -ForegroundColor Yellow
    Push-Location $RepoRoot
    try {
        mvn clean package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "mvn 打包失败" }
    } finally {
        Pop-Location
    }
} else {
    Write-Host "`n⏭️  跳过本地打包，直接使用: $JarPath" -ForegroundColor Yellow
}

if (-not (Test-Path $JarPath)) {
    Write-Host "❌ 找不到 JAR: $JarPath" -ForegroundColor Red
    exit 1
}

$sizeMb = [math]::Round((Get-Item $JarPath).Length / 1MB, 1)
Write-Host "`n📦 本地 JAR: $JarPath ($sizeMb MB)" -ForegroundColor Green
Write-Host "🚀 上传中 -> ${RemoteDir}/${RemoteJarName} ..." -ForegroundColor Yellow
Copy-ToServer -LocalPath $JarPath -RemotePath "$RemoteDir/$RemoteJarName"

Write-Host "`n🔨 服务器重建镜像并重启 $ComposeService ..." -ForegroundColor Yellow
Invoke-RemoteCompose "build $ComposeService"
Invoke-RemoteCompose "up -d $ComposeService"

Write-Host "`n⏳ 等待 5 秒..." -ForegroundColor Yellow
Start-Sleep -Seconds 5

Write-Host "`n📊 容器状态：" -ForegroundColor Cyan
Invoke-RemoteCompose "ps $ComposeService"

Write-Host "`n📝 最近日志：" -ForegroundColor Cyan
Invoke-RemoteCompose "logs --tail 30 $ComposeService"

Write-Host "`n✅ chexuan-mtt 更新完成" -ForegroundColor Green
