# eNSP NMS 学校交付安装（Windows + Docker Desktop）
# 用法：在仓库根目录或 deploy 目录执行
#   powershell -ExecutionPolicy Bypass -File deploy/scripts/install.ps1

$ErrorActionPreference = "Stop"
$DeployDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $DeployDir

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    Write-Error "未检测到 Docker。请先安装 Docker Desktop 并确认 docker 命令可用。"
}

if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "已生成 deploy/.env，请按学校修改授权与密码后重新执行本脚本。" -ForegroundColor Yellow
    Write-Host "必改项：DB_PASSWORD、MYSQL_ROOT_PASSWORD、AUTH_JWT_SECRET、NMS_LICENSE_*"
    exit 2
}

Write-Host "==> 构建并启动 eNSP NMS ..." -ForegroundColor Cyan
docker compose --env-file .env up -d --build

Write-Host ""
Write-Host "安装完成。" -ForegroundColor Green
Write-Host "浏览器访问: http://localhost  （若 WEB_PORT 已改，请用对应端口）"
Write-Host "默认账号: admin / Admin@123  （首次登录须改密）"
Write-Host "查看日志: docker compose -f `"$DeployDir\docker-compose.yml`" logs -f backend"
