<#
.SYNOPSIS
    构建 AVstar 单个品牌包 —— 日常修 bug 用这个，最快（不换图标）。

.DESCRIPTION
    三包共存靠 app/build.gradle 的 -Pbrand 切换 applicationId 和 app_name：
        juchang  -> com.avstar.tv.juchang   AV剧场
        xingxiu  -> com.avstar.tv.xingxiu   AV星秀
        xiuchang -> com.avstar.tv.xiuchang  AV秀场
    但 launcher 图标只有一份（gen_avstar_icons.py 固定写进 app/src/main/res），
    所以换了品牌却没换图标的话，打出来的包名字对、图标错。要换图标必须加 -RefreshIcon。

    脚本文件必须存成「带 BOM 的 UTF-8」，否则 Windows PowerShell 5.1 会按 GBK 读，
    中文字符的字节被拆坏、引号失配，直接 ParserError。

.EXAMPLE
    .\build_one.ps1
        打 AV剧场，沿用现存的 launcher 图标（最快）

    .\build_one.ps1 -Brand xingxiu
        打 AV星秀，沿用现存的 launcher 图标

    .\build_one.ps1 -Brand xingxiu -RefreshIcon
        打 AV星秀，并先用 ai 文件夹里的图片重新生成 launcher 图标

    .\build_one.ps1 -OutDir $null
        只构建，不复制 APK 出来
#>
[CmdletBinding()]
param(
    [ValidateSet('juchang', 'xingxiu', 'xiuchang')]
    [string]$Brand = 'juchang',

    [switch]$RefreshIcon,

    [string]$OutDir = 'C:\Users\78139\Desktop\ai\apk'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$ProjectRoot = $PSScriptRoot
$IconDir     = 'C:\Users\78139\Desktop\ai'
$IconMap     = @{
    juchang  = 'AV剧场.jpg'
    xingxiu  = 'AV星秀.jpg'
    xiuchang = 'AV秀场.jpg'
}
$ApkRelPath = 'app\build\outputs\apk\mobileArm64_v8a\release\mobile-arm64_v8a.apk'

# ---- 构建环境（和验证过的命令行一致）----
$env:PATH          = "D:\python310;$env:PATH"
$env:JAVA_HOME     = 'D:\tmp_wk\jdk21\jdk-21.0.12.1+1'
$env:ANDROID_HOME  = 'D:\Android\sdk'
$env:PATH          = "$env:JAVA_HOME\bin;$env:PATH"
# jitpack 走直连不经过代理，其余走 127.0.0.1:2080
$ProxyOpts = '-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=2080 ' +
             '-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=2080 ' +
             '-Dhttp.nonProxyHosts="*jitpack.io*" -Dhttps.nonProxyHosts="*jitpack.io*"'
$env:GRADLE_OPTS        = "-Xmx4g $ProxyOpts"
$env:JAVA_TOOL_OPTIONS  = $ProxyOpts

Write-Host ''
Write-Host "=== [$Brand] AVstar 单包构建 ===" -ForegroundColor Cyan

if ($RefreshIcon) {
    $src = Join-Path $IconDir $IconMap[$Brand]
    Write-Host "生成 launcher 图标: $($IconMap[$Brand])"
    python (Join-Path $IconDir 'gen_avstar_icons.py') $src $Brand
    if ($LASTEXITCODE -ne 0) { throw '图标生成失败' }
} else {
    Write-Host '[i] 沿用 app/src/main/res 里现存的 launcher 图标。' -ForegroundColor DarkGray
    Write-Host '    若该图标不属于当前品牌，请加 -RefreshIcon。' -ForegroundColor DarkGray
}

Push-Location $ProjectRoot
try {
    & .\gradlew.bat :app:assembleMobileArm64_v8aRelease "-Pbrand=$Brand" -PfastRelease=true --no-daemon
    $rc = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($rc -ne 0) { throw "Gradle 构建失败（exit $rc），brand=$Brand" }

$apk = Join-Path $ProjectRoot $ApkRelPath
if (-not (Test-Path $apk)) { throw "没找到 APK：$apk" }

if ($OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $name = [System.IO.Path]::GetFileNameWithoutExtension($IconMap[$Brand])
    $dest = Join-Path $OutDir "$name.apk"
    Copy-Item $apk $dest -Force
    Write-Host ("APK: {0}  ({1} MB)" -f $dest, [math]::Round((Get-Item $dest).Length / 1MB, 1)) -ForegroundColor Green
} else {
    Write-Host ("APK: {0}" -f $apk) -ForegroundColor Green
}