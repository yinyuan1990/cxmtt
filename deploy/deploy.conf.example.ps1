# ============================================================
# 部署配置模板。复制为 deploy.conf.ps1 后填真实值（该文件已在 .gitignore，不会提交）：
#   Copy-Item deploy\deploy.conf.example.ps1 deploy\deploy.conf.ps1
# ============================================================

$ServerHost = "47.122.115.33"
$ServerUser = "root"
$ServerPort = 22
$ServerPass = "改成真实密码"

# 首次连接后可从 plink 报错信息里拿到指纹填这里，避免每次都要手动确认 host key
$HostKeyFingerprint = "SHA256:14GGJO42UVPRtNBHullNW9IBLxTCvnF1e3XFMqj8ap0"

# 服务器上 docker compose 项目目录（app/mtt/mysql/redis/nginx 都在这个 compose 里）
$RemoteDir = "/home/fzcx/chexuan"

# docker compose 里 mtt 服务名 + 镜像内 jar 文件名
$ComposeService = "mtt"
$RemoteJarName = "chexuan-mtt.jar"

# 本地 jar 产物路径（mvn package 后生成）
$LocalJarPath = "target\chexuan-mtt.jar"
