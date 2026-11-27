# Reelix - Windows 侧验收脚本
#
# 用途：不连电视，只在这台电脑上完成"这一版代码有没有坏"的全部检查。
# 全部通过后，再把 dist 里的 apk 丢到电视上做真机验收（焦点/解码/遥控器）。
#
# 用法：
#   pwsh -File scripts\verify.ps1              # 测试 + 构建
#   pwsh -File scripts\verify.ps1 -SkipBuild   # 只跑测试（改逻辑时最快）
#   pwsh -File scripts\verify.ps1 -Lint        # 额外跑 lint
#
# 退出码：0 = 全绿，非 0 = 有问题（可直接接 CI 或 git hook）

param(
    [switch]$SkipBuild,
    [switch]$Lint,
    [string]$GradleHome = "D:\AndroidDev\gradle-home",
    [string]$JavaHome   = "D:\AndroidDev\jdk"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

# ---- 环境（这台机器上 Gradle 的 user home 和 JDK 都不在默认位置）----
if (Test-Path $GradleHome) { $env:GRADLE_USER_HOME = $GradleHome }
if (Test-Path $JavaHome) {
    $env:JAVA_HOME = $JavaHome
    $env:PATH = "$JavaHome\bin;$env:PATH"
}

$gradlew = Join-Path $root "gradlew.bat"
$failures = @()

function Step($name, [scriptblock]$action) {
    Write-Host ""
    Write-Host "==> $name" -ForegroundColor Cyan
    $sw = [Diagnostics.Stopwatch]::StartNew()
    & $action
    if ($LASTEXITCODE -ne 0) {
        $script:failures += $name
        Write-Host "    FAILED ($([int]$sw.Elapsed.TotalSeconds)s)" -ForegroundColor Red
    } else {
        Write-Host "    ok ($([int]$sw.Elapsed.TotalSeconds)s)" -ForegroundColor Green
    }
}

# ---- 1. 纯逻辑单测（不依赖设备，这是 Windows 侧最值钱的一道闸）----
Step "unit tests (JVM)" {
    & $gradlew :app:testDebugUnitTest --console=plain -q
}

# ---- 2. 编译 + 打包 ----
if (-not $SkipBuild) {
    Step "assembleDebug" {
        & $gradlew :app:assembleDebug --console=plain -q
    }
}

# ---- 3. 可选 lint ----
if ($Lint) {
    Step "lintDebug" {
        & $gradlew :app:lintDebug --console=plain -q
    }
}

# ---- 汇总 ----
Write-Host ""
$report = Join-Path $root "app\build\reports\tests\testDebugUnitTest\index.html"
$resultsDir = Join-Path $root "app\build\test-results\testDebugUnitTest"

if (Test-Path $resultsDir) {
    # 【不要用 [xml] 强转】测试名是中文，XML 声明是 UTF-8，
    # 但 Windows PowerShell 5.1 会用系统代码页读取，强转必炸在
    # "is an unexpected token"。这里只做正则抓数字，稳得多。
    $total = 0; $failed = 0
    Get-ChildItem $resultsDir -Filter *.xml | ForEach-Object {
        $raw = Get-Content $_.FullName -Raw -Encoding UTF8
        $m = [regex]::Match($raw, 'tests="(\d+)"\s+skipped="\d+"\s+failures="(\d+)"\s+errors="(\d+)"')
        if ($m.Success) {
            $total  += [int]$m.Groups[1].Value
            $failed += [int]$m.Groups[2].Value + [int]$m.Groups[3].Value
        }
    }
    Write-Host "单元测试：$total 个，失败 $failed 个" -ForegroundColor $(if ($failed -eq 0) { "Green" } else { "Red" })
    Write-Host "报告：$report"
}

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
if (Test-Path $apk) {
    $size = [math]::Round((Get-Item $apk).Length / 1MB, 1)
    Write-Host "APK：$apk ($size MB)"
    Write-Host ""
    Write-Host "装到电视（设备在线时）：" -ForegroundColor Yellow
    Write-Host "  D:\AndroidDev\sdk\platform-tools\adb.exe install -r `"$apk`""
}

if ($failures.Count -gt 0) {
    Write-Host ""
    Write-Host "失败项：$($failures -join ', ')" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "全部通过。" -ForegroundColor Green
exit 0
