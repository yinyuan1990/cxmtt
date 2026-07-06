# ============================================================
# chexuan-mtt 部署公共函数（PowerShell + PuTTY plink/pscp）
# 被 update.ps1 / restart.ps1 / stop.ps1 dot-source 引用，不要直接运行
# ============================================================

# 终端默认 GBK 代码页会把中文输出显示为乱码，切到 UTF-8 只影响本次会话
try {
    chcp 65001 > $null
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
} catch { }

$ScriptDir = $PSScriptRoot
$RepoRoot = Split-Path $ScriptDir -Parent

$ConfFile = Join-Path $ScriptDir "deploy.conf.ps1"
if (-not (Test-Path $ConfFile)) {
    Write-Host "❌ 找不到 $ConfFile" -ForegroundColor Red
    Write-Host "   请先复制模板并填真实密码：" -ForegroundColor Yellow
    Write-Host "   Copy-Item deploy\deploy.conf.example.ps1 deploy\deploy.conf.ps1" -ForegroundColor Yellow
    exit 1
}
. $ConfFile

foreach ($name in @("ServerHost", "ServerUser", "ServerPort", "ServerPass", "RemoteDir", "ComposeService", "RemoteJarName", "LocalJarPath")) {
    if (-not (Get-Variable -Name $name -ErrorAction SilentlyContinue)) {
        Write-Host "❌ deploy.conf.ps1 缺少变量: $name" -ForegroundColor Red
        exit 1
    }
}

$PlinkExe = "C:\Program Files\PuTTY\plink.exe"
$PscpExe = "C:\Program Files\PuTTY\pscp.exe"
if (-not (Test-Path $PlinkExe)) { $PlinkExe = "plink" }
if (-not (Test-Path $PscpExe)) { $PscpExe = "pscp" }

function Invoke-RemoteCommand {
    param([Parameter(Mandatory)][string]$Command)

    $hostkeyArgs = @()
    if ($HostKeyFingerprint) { $hostkeyArgs = @("-hostkey", $HostKeyFingerprint) }

    & $PlinkExe -batch -ssh @hostkeyArgs -P $ServerPort "$ServerUser@$ServerHost" -pw $ServerPass $Command
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ 远程命令失败 (exit=$LASTEXITCODE): $Command" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

function Invoke-RemoteCompose {
    param([Parameter(Mandatory)][string]$Args)
    Invoke-RemoteCommand "cd '$RemoteDir' && docker compose $Args"
}

function Copy-ToServer {
    param(
        [Parameter(Mandatory)][string]$LocalPath,
        [Parameter(Mandatory)][string]$RemotePath
    )
    $hostkeyArgs = @()
    if ($HostKeyFingerprint) { $hostkeyArgs = @("-hostkey", $HostKeyFingerprint) }

    & $PscpExe -batch @hostkeyArgs -P $ServerPort -pw $ServerPass $LocalPath "$ServerUser@${ServerHost}:$RemotePath"
    if ($LASTEXITCODE -ne 0) {
        Write-Host "❌ 上传失败 (exit=$LASTEXITCODE): $LocalPath -> $RemotePath" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}
