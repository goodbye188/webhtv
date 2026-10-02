<#
.SYNOPSIS
    一条命令出三个可共存的包：AV剧场 / AV星秀 / AV秀场。定版时用这个。

.DESCRIPTION
    三个品牌串行构建，原因是 launcher 图标只有一份物理文件
    （app/src/main/res/mipmap-*/ic_launcher.png），所以只能「换图标 → 编译 → 复制 APK」
    循环三次，没法一次编译产出三份。

    每个包：
        applicationId  com.avstar.tv.{juchang|xingxiu|xiuchang}
        app_name       AV剧场 / AV星秀 / AV秀场
        launcher       ai 文件夹里对应的 jpg，原样缩放到各密度

    跑完之后 app/src/main/res 里留下的是最后一个包（AV秀场）的图标。
    之后单独打包一定要用 build_one.ps1 -RefreshIcon，否则名字对图标错。

.EXAMPLE
    .\build_all.ps1
    .\build_all.ps1 -OutDir D:\apk
#>
[CmdletBinding()]
param(
    [string]$OutDir = 'C:\Users\78139\Desktop\ai\apk'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8

$ProjectRoot = $PSScriptRoot
$One = Join-Path $ProjectRoot 'build_one.ps1'
if (-not (Test-Path $One)) { throw "找不到 build_one.ps1：$One" }

$Brands = @('juchang', 'xingxiu', 'xiuchang')
$Done = @()

foreach ($b in $Brands) {
    Write-Host ''
    Write-Host "################  $b  ################" -ForegroundColor Yellow
    # 用子进程 + ExecutionPolicy Bypass 调用，避免本机执行策略是 Restricted 时中断
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $One -Brand $b -RefreshIcon -OutDir $OutDir
    if ($LASTEXITCODE -ne 0) { throw "品牌 $b 构建失败（exit $LASTEXITCODE），已中止，后续品牌未构建。" }
    $Done += $b
}

Write-Host ''
Write-Host '================ 全部完成 ================' -ForegroundColor Green
Write-Host ("已构建: " + ($Done -join ', ')) -ForegroundColor Green
if ($OutDir) {
    Get-ChildItem $OutDir -Filter *.apk |
        Select-Object Name, @{n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } }, LastWriteTime |
        Format-Table -AutoSize
}
Write-Host '提示：app/src/main/res 现在留的是最后一个包的图标，单独打包请加 -RefreshIcon。' -ForegroundColor DarkGray