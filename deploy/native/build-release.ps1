# 在开发机打包「无 Docker 教师机交付目录」
# 需要：JDK17、Maven、Node18+
# 产出：deploy/native/ensp-nms-*.jar + deploy/native/web/

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$NativeDir = $PSScriptRoot
$Backend = Join-Path $Root "backend"
$Frontend = Join-Path $Root "frontend"

Write-Host "==> 构建后端 ..." -ForegroundColor Cyan
Push-Location $Backend
try {
    if (Get-Command mvn -ErrorAction SilentlyContinue) {
        mvn -DskipTests package
    } else {
        Write-Error "未找到 mvn，请安装 Maven 或在已配置环境的机器上打包"
    }
} finally {
    Pop-Location
}

$built = Get-ChildItem (Join-Path $Backend "target") -Filter "ensp-nms-*.jar" |
    Where-Object { $_.Name -notlike "*-plain.jar" } |
    Select-Object -First 1
if (-not $built) { Write-Error "后端 JAR 未生成" }

Copy-Item $built.FullName -Destination (Join-Path $NativeDir $built.Name) -Force
Write-Host "已复制 $($built.Name)"

Write-Host "==> 构建前端 ..." -ForegroundColor Cyan
Push-Location $Frontend
try {
    if (-not (Test-Path "node_modules")) { npm ci }
    npm run build
} finally {
    Pop-Location
}

$webOut = Join-Path $NativeDir "web"
if (Test-Path $webOut) { Remove-Item $webOut -Recurse -Force }
Copy-Item (Join-Path $Frontend "dist") $webOut -Recurse
Write-Host "已复制 frontend/dist -> deploy/native/web"

if (-not (Test-Path (Join-Path $NativeDir "env.ps1"))) {
    Copy-Item (Join-Path $NativeDir "env.example.ps1") (Join-Path $NativeDir "env.ps1")
    Write-Host "已生成 env.ps1，交付前请按学校改密码与授权" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "交付目录就绪: $NativeDir" -ForegroundColor Green
Write-Host "拷到教师机后执行: .\start.ps1"
