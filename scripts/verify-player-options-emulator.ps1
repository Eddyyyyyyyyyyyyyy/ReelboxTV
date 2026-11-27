param([string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw 'Local emulator required' }
$adb = 'D:\AndroidDev\Sdk\platform-tools\adb.exe'
$evidence = Join-Path (Split-Path -Parent $PSScriptRoot) 'work'
$results = [ordered]@{}
function Keys([int[]]$Codes) {
    & $adb -s $Device shell input keyevent @Codes | Out-Null
    if ($LASTEXITCODE) { throw 'Key input failed' }
}
function UI([string]$Name) {
    for ($attempt=0; $attempt -lt 3; $attempt++) {
        & $adb -s $Device shell rm -f /sdcard/reelix-options.xml | Out-Null
        if ($LASTEXITCODE) { throw 'Cannot clear UI capture' }
        & $adb -s $Device shell uiautomator dump /sdcard/reelix-options.xml | Out-Null
        if ($LASTEXITCODE) { continue }
        & $adb -s $Device pull /sdcard/reelix-options.xml (Join-Path $evidence "$Name.xml") 2>$null | Out-Null
        if (!$LASTEXITCODE) { return [xml](Get-Content (Join-Path $evidence "$Name.xml") -Raw -Encoding UTF8) }
    }
    throw "Fresh capture failed: $Name"
}
function Focus([xml]$Tree) { return $Tree.SelectSingleNode('//node[@focused="true"]') }
function Assert([bool]$Condition,[string]$Message) { if (!$Condition) { throw $Message } }
function Launch([string[]]$Extras = @()) {
    & $adb -s $Device shell am force-stop com.reelix.app
    & $adb -s $Device shell am start -n com.reelix.app/.debug.PlayerDiagnosticsActivity @Extras | Out-Null
    if ($LASTEXITCODE) { throw 'Fixture launch failed' }
}
function Seconds([xml]$Tree) {
    $text = $Tree.SelectSingleNode('//node[contains(@text," / ")]').text
    $parts = ($text -split ' / ')[0] -split ':'
    return [int]$parts[0]*60+[int]$parts[1]
}

Launch
$null = UI 'options-start'
Keys @(23,4,22,20)
Keys (@(22)*5)
$before = UI 'options-before-reverse'
$oldFocus = (Focus $before).'content-desc'
Keys @(19,19,23)
$tree = UI 'options-reverse-button'
Assert ((Focus $tree).text -eq '倒序 ⇅') 'Reverse toggle must keep button focus'
Keys @(20,20)
$tree = UI 'options-reverse-position'
Assert ((Focus $tree).'content-desc' -eq $oldFocus) 'Reverse must preserve episode identity'
Keys @(22)
$tree = UI 'options-reverse-right'
Assert ((Focus $tree).'content-desc' -eq '第5集') 'Reversed RIGHT must move from episode 6 to episode 5'
Assert ((Focus $tree).text -eq '5') 'Visible episode number must describe the real episode'
$results.reverse = 'pass'

# Return to normal order before checking decoder state restoration.
Keys @(19,19,23,20,20,4)
$paused = UI 'options-before-decoder'
$position = Seconds $paused
Keys @(20)
$null = UI 'options-decoder-panel-open'
Keys @(20,20,20)
$tree = UI 'options-decoder-ready'
Assert ((Focus $tree).'content-desc' -eq '自动') 'Decoder rail must open on the active mode'
Keys @(22,22,23)
$tree = UI 'options-software-decoder'
Assert ((Focus $tree).'content-desc' -eq '软解') 'Software decoder selection must keep focus'
Keys @(4)
$tree = UI 'options-software-restored'
Assert ((Seconds $tree) -eq $position) 'Decoder switch must preserve paused playback position'
Assert ($null -ne $tree.SelectSingleNode('//node[@content-desc="播放"]')) 'Decoder switch must preserve pause state'
$log = & $adb -s $Device logcat -d -t 300 -s ReelixDecoder:I
Assert (($log -join "`n") -match 'mode=SOFTWARE video=') 'Software decoder must actually initialize'
$results.decoderRestore = 'pass'

Launch @('--es','uri','file:///data/user/0/com.reelix.app/files/player-multiaudio.mp4')
$null = UI 'options-audio-start'
Keys @(23,20)
$null = UI 'options-audio-panel-open'
Keys @(20,20,20,20)
$tree = UI 'options-audio-ready'
Assert ((Focus $tree).'content-desc' -eq '自动选择') 'Audio rail must be reachable'
Keys @(22,22,23)
$tree = UI 'options-audio-english'
Assert ((Focus $tree).'content-desc' -match '英语') 'Second audio track must be reachable'
Assert ((Focus $tree).text.StartsWith('✓')) 'English audio track must be selected'
Keys @(21,21,23)
$tree = UI 'options-audio-auto'
Assert ((Focus $tree).'content-desc' -eq '自动选择') 'Audio automatic selection must be reachable'
Assert ((Focus $tree).text.StartsWith('✓')) 'Automatic audio selection must be applied'
$results.audio = 'pass'

Launch @('--es','uri','file:///data/user/0/com.reelix.app/files/player-multiaudio.mp4','--el','outroMs','6000','--ei','episodes','2')
Start-Sleep -Seconds 18
Keys @(23,20)
$tree = UI 'options-auto-next'
Assert ((Focus $tree).'content-desc' -eq '第2集') 'Outro boundary must trigger automatic next episode'
$results.outroAutoNext = 'pass'

Launch @('--es','screen','library')
$null = UI 'options-library-initial'
Keys (@(22)*3)
Keys @(23)
$tree = UI 'options-library-refreshing'
Assert ($tree.OuterXml.Contains('刷新中')) 'Refresh must show an in-progress state'
Start-Sleep -Seconds 5
$tree = UI 'options-library-complete'
Assert ($tree.OuterXml.Contains('刷新完成：2 部作品')) 'Refresh must report the new library count'
Assert ($tree.OuterXml.Contains('电影A') -and $tree.OuterXml.Contains('电影B')) 'Refresh must update posters'
Keys @(23)
Start-Sleep -Seconds 8
$tree = UI 'options-library-failed'
Assert ($tree.OuterXml.Contains('fixture scan failed')) 'Failure must remain visible'
Assert ($tree.OuterXml.Contains('电影A') -and $tree.OuterXml.Contains('电影B')) 'Failed refresh must keep previous posters'
$results.libraryRefresh = 'pass'
$libraryCard = $tree.SelectSingleNode('//node[@focusable="true" and @content-desc="电影A"]')

Launch @('--es','screen','home')
$tree = UI 'options-home-posters'
$homeCard = $tree.SelectSingleNode('//node[@focusable="true" and @content-desc="历史影片1"]')
function CardWidth($Node) {
    Assert ($null -ne $Node) 'Poster node missing'
    $numbers = [regex]::Matches($Node.bounds, '\d+') | ForEach-Object { [int]$_.Value }
    return $numbers[2] - $numbers[0]
}
Assert ((CardWidth $homeCard) -eq (CardWidth $libraryCard)) 'Home history posters must match library poster width'
$results.posterSize = 'pass'

Launch @('--ez','networkFixture','true')
Start-Sleep -Seconds 4
Keys @(23)
$null = UI 'options-network-active'
$appPid = ((& $adb -s $Device shell pidof com.reelix.app) | Out-String).Trim()
Assert ($appPid -match '^\d+$') 'Network fixture process missing'
$traffic = (& $adb -s $Device logcat -d --pid=$appPid -s ReelixTraffic:D) -join "`n"
$samples = [regex]::Matches($traffic, 'bytesPerSecond=(\d+)') | ForEach-Object { [long]$_.Groups[1].Value }
Assert (@($samples | Where-Object { $_ -gt 0 }).Count -gt 0) 'Network bytes must update before the file finishes loading'
Start-Sleep -Seconds 10
$tree = UI 'options-network-idle'
Assert ($tree.OuterXml.Contains('0.0 KB/s')) 'Network rate must return to zero when buffered playback stops downloading'
Assert ($tree.OuterXml.Contains('缓冲余量')) 'Buffered seconds must be clearly labelled'
$results.networkSampling = 'pass'
$results | ConvertTo-Json | Set-Content (Join-Path $evidence 'player-options-emulator-results.json') -Encoding UTF8
$results | ConvertTo-Json
