# 打包交接目录，排除调试残留与本机隐私
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts\export-handoff.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\export-handoff.ps1 -Dest D:\给同事\Reelix
#
# 产出：一个干净的目录 + 同名的 .zip，可直接交给另一个 Agent / 另一台机器。

param(
    [string]$Dest = "D:\Reelix-handoff",
    [switch]$Zip,
    [switch]$Force
)

$ErrorActionPreference = 'Stop'
$src = Split-Path -Parent $PSScriptRoot

if (Test-Path $Dest) {
    if (-not $Force) {
        Write-Host "目标已存在：$Dest" -ForegroundColor Yellow
        Write-Host "加 -Force 覆盖，或换一个 -Dest" -ForegroundColor Yellow
        exit 1
    }
    Remove-Item $Dest -Recurse -Force
}

# 排除项说明：
#   _dbg / _shots     调试残留（抓包、截图），几百 MB，与源码无关
#   .gradle/build/... 构建缓存，新机器会重新生成
#   .cxx              CMake/NDK 中间产物（31MB），重新编译即生成
#   local.properties  本机 SDK 路径
#   .alist_token      真实 Alist 凭据，绝不能外传
$excludeDirs = @('_dbg', '_shots', '.gradle', 'build', '.kotlin', '.idea', '.git', '.cxx')
$excludeFiles = @('local.properties', '.alist_token', '*.iml', '*.hprof', 'Reelix-2.3.0-debug.apk')

Write-Host "源目录：$src" -ForegroundColor Cyan
Write-Host "目标：  $Dest" -ForegroundColor Cyan

$xd = $excludeDirs | ForEach-Object { "/XD"; $_ }
$xf = $excludeFiles | ForEach-Object { "/XF"; $_ }

$robocopyArgs = @($src, $Dest, '/E', '/NFL', '/NDL', '/NJH', '/NJS', '/NP') + $xd + $xf

& robocopy @robocopyArgs | Out-Null

# robocopy 成功码是 0-7，8 以上才是真失败
if ($LASTEXITCODE -ge 8) {
    Write-Host "复制失败（robocopy exit $LASTEXITCODE）" -ForegroundColor Red
    exit $LASTEXITCODE
}

# 补一个空目录占位说明，避免新机器上 local.properties 缺失时不知道怎么办
@'
# 这个文件由新机器自己生成（或手写一行 sdk.dir）

sdk.dir=D:\\AndroidDev\\sdk

# 换机器时改成实际的 Android SDK 路径。
# JAVA_HOME / GRADLE_USER_HOME 通过环境变量传，不写在这里。
'@ | Set-Content (Join-Path $Dest 'local.properties.example') -Encoding UTF8

# 统计
$files = Get-ChildItem $Dest -Recurse -File -ErrorAction SilentlyContinue
$kt = $files | Where-Object { $_.Extension -eq '.kt' }
$size = [math]::Round((($files | Measure-Object Length -Sum).Sum) / 1MB, 1)

Write-Host ""
Write-Host "完成：$($files.Count) 个文件 / $size MB" -ForegroundColor Green
Write-Host "  Kotlin 源码：$($kt.Count) 个" -ForegroundColor Green
Write-Host "  结构：$Dest" -ForegroundColor Green

# 体检：确认敏感文件没被带出去
$leak = @()
if (Test-Path (Join-Path $Dest '.alist_token')) { $leak += '.alist_token（真实凭据）' }
if (Test-Path (Join-Path $Dest 'local.properties')) { $leak += 'local.properties（本机路径）' }
if (Test-Path (Join-Path $Dest '_dbg')) { $leak += '_dbg（调试残留）' }
if (Test-Path (Join-Path $Dest '_shots')) { $leak += '_shots（截图）' }

if ($leak.Count -gt 0) {
    Write-Host ""
    Write-Host "警告：以下应当排除但仍在目录里：" -ForegroundColor Red
    $leak | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    exit 2
}

Write-Host "体检通过：敏感文件与调试残留均未包含" -ForegroundColor Green

if ($Zip) {
    $zipPath = "$Dest.zip"
    if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
    Compress-Archive -Path (Join-Path $Dest '*') -DestinationPath $zipPath
    $zipMb = [math]::Round((Get-Item $zipPath).Length / 1MB, 1)
    Write-Host "压缩包：$zipPath（$zipMb MB）" -ForegroundColor Green
}
