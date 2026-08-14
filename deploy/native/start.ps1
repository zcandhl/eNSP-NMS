# 教师机无 Docker 启动（需已安装 JDK17 + MySQL，并准备好 app.jar 与 web/）
# 用法：在 deploy/native 目录执行 .\start.ps1

$ErrorActionPreference = "Stop"
$NativeDir = $PSScriptRoot
Set-Location $NativeDir

if (Test-Path "$NativeDir\env.ps1") {
    . "$NativeDir\env.ps1"
} elseif (Test-Path "$NativeDir\env.example.ps1") {
    Write-Host "未找到 env.ps1，请先: Copy-Item env.example.ps1 env.ps1 并修改密码/授权" -ForegroundColor Yellow
    exit 2
}

$jar = Get-ChildItem -Path $NativeDir -Filter "*.jar" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
    Select-Object -First 1

if (-not $jar) {
    Write-Error "未找到 JAR。请将 ensp-nms-*.jar 放到本目录，或先执行 build-release.ps1"
}

if (-not (Test-Path "$NativeDir\web\index.html")) {
    Write-Error "未找到 web\index.html。请先构建前端并复制到 deploy\native\web\"
}

if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    Write-Error "未找到 java，请安装 JDK 17+ 并加入 PATH"
}

Write-Host "启动: $($jar.Name)  profile=$($env:SPRING_PROFILES_ACTIVE)  port=$($env:SERVER_PORT)" -ForegroundColor Cyan
Write-Host "浏览器访问: http://localhost:$($env:SERVER_PORT)"
Write-Host "默认账号: admin / Admin@123（首次须改密）"
java -jar $jar.FullName
