# 备份 MySQL 数据卷对应库（需已启动）
$ErrorActionPreference = "Stop"
$DeployDir = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $DeployDir

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$out = Join-Path $DeployDir "backups"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$file = Join-Path $out "ensp_nms-$stamp.sql"

Write-Host "导出数据库到 $file ..."
docker compose --env-file .env exec -T mysql sh -c 'mysqldump -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" ensp_nms' | Set-Content -Encoding utf8 $file
Write-Host "完成: $file" -ForegroundColor Green
