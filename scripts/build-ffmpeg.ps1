param(
    [string]$NativeRoot = 'D:\AndroidDev\reelix-native',
    [string]$Ndk = 'D:\AndroidDev\Sdk\ndk\27.0.12077973'
)
$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $PSScriptRoot
$bash = Join-Path $NativeRoot 'msys64\usr\bin\bash.exe'
if (!(Test-Path -LiteralPath $bash)) { throw 'Install portable MSYS2 and make in NativeRoot as documented in media3-ffmpeg/README.md.' }
function MsysPath([string]$Path) {
    $absolute = [IO.Path]::GetFullPath($Path)
    return '/' + $absolute.Substring(0,1).ToLowerInvariant() + $absolute.Substring(2).Replace('\','/')
}
$env:MSYSTEM = 'MSYS'
& $bash -lc ('bash "{0}/scripts/build-ffmpeg-windows.sh" "{1}" "{2}" "{0}"' -f (MsysPath $project), (MsysPath $NativeRoot), (MsysPath $Ndk))
if ($LASTEXITCODE) { throw "FFmpeg build failed: $LASTEXITCODE" }
