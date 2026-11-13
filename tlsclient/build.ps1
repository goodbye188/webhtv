# 星秀 TLS 指纹桥内核 —— 交叉编译脚本（产物丢进 app jniLibs，模式与 libmihomo.so 相同）。
# 用法: powershell -NoProfile -ExecutionPolicy Bypass -File build.ps1 [-Out <so路径>]
# 依赖: D:\Go\bin\go.exe（1.22+），模块下载走本机代理 7890。
param(
    [string]$Out = "$PSScriptRoot\..\app\src\main\jniLibs\arm64-v8a\libtlsclient.so"
)

$go = 'D:\Go\bin\go.exe'
if (-not (Test-Path $go)) { throw "go not found at $go" }

$env:GOOS = 'android'
$env:GOARCH = 'arm64'
$env:CGO_ENABLED = '0'
$env:GOFLAGS = '-mod=mod'
# 模块下载代理（与 build_one.ps1 同一出口）
$env:GOPROXY = 'https://goproxy.cn,https://proxy.golang.org,direct'

Push-Location $PSScriptRoot
try {
    & $go mod tidy
    if ($LASTEXITCODE -ne 0) { throw "go mod tidy failed" }
    & $go build -trimpath -ldflags '-s -w' -o $Out .
    if ($LASTEXITCODE -ne 0) { throw "go build failed" }
} finally {
    Pop-Location
}

Write-Host "built: $Out ($([math]::Round((Get-Item $Out).Length/1MB,1)) MB)"
