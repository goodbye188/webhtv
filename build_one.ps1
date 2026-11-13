<#
.SYNOPSIS
    构建 AVstar 单包（星秀）—— 现在只有这一个包。

.DESCRIPTION
    2026-11-07 起不再打三包，app/build.gradle 里已写死唯一品牌：
        applicationId  com.avstar.tv.juchang
        app_name       星秀
        launcher 图标  已定版（folder_videos_16534.png），不再有换图标动作，-RefreshIcon 参数已删除。

    gradlew 单独调用也不再需要 -Pbrand（三品牌切换连同参数校验一起删了）。

    脚本文件必须存成「带 BOM 的 UTF-8」，否则 Windows PowerShell 5.1 会按 GBK 读，
    中文字符的字节被拆坏、引号失配，直接 ParserError。

.EXAMPLE
    .\build_one.ps1
        构建并复制 APK 到 C:\Users\78139\Desktop\ai\apk

    .\build_one.ps1 -OutDir $null
        只构建，不复制 APK 出来
#>
[CmdletBinding()]
param(
    [string]$OutDir = 'C:\Users\78139\Desktop\ai\apk'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$ProjectRoot = $PSScriptRoot
$ApkRelPath  = 'app\build\outputs\apk\mobileArm64_v8a\release\mobile-arm64_v8a.apk'

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
Write-Host '=== AVstar 单包构建（星秀 / com.avstar.tv.juchang）===' -ForegroundColor Cyan

Push-Location $ProjectRoot
try {
    & .\gradlew.bat :app:assembleMobileArm64_v8aRelease -PfastRelease=true --no-daemon
    $rc = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($rc -ne 0) { throw "Gradle 构建失败（exit $rc）" }

$apk = Join-Path $ProjectRoot $ApkRelPath
if (-not (Test-Path $apk)) { throw "没找到 APK：$apk" }

if ($OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $dest = Join-Path $OutDir '星秀.apk'
    Copy-Item $apk $dest -Force
    Write-Host ("APK: {0}  ({1} MB)" -f $dest, [math]::Round((Get-Item $dest).Length / 1MB, 1)) -ForegroundColor Green
} else {
    Write-Host ("APK: {0}" -f $apk) -ForegroundColor Green
}
