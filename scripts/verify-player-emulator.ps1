param([string]$Device = 'emulator-5554')
$ErrorActionPreference = 'Stop'
if ($Device -notmatch '^emulator-\d+$') { throw 'This check only supports a local emulator.' }
$adb = 'D:\AndroidDev\Sdk\platform-tools\adb.exe'
$projectRoot = Split-Path -Parent $PSScriptRoot
$evidence = Join-Path $projectRoot 'work'
$results = [ordered]@{}
function SendKeys([int[]]$Keys) {
    & $adb -s $Device shell input keyevent @Keys | Out-Null
    if ($LASTEXITCODE) { throw 'Key input failed' }
}
function ReadUI([string]$Name) {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        # A failed dump must never reuse the previous step's XML as fresh evidence.
        & $adb -s $Device shell rm -f /sdcard/reelix-regression.xml | Out-Null
        if ($LASTEXITCODE) { throw 'Cannot clear previous UI capture' }
        & $adb -s $Device shell uiautomator dump /sdcard/reelix-regression.xml | Out-Null
        if ($LASTEXITCODE) { continue }
        & $adb -s $Device pull /sdcard/reelix-regression.xml (Join-Path $evidence "$Name.xml") 2>$null | Out-Null
        if ($LASTEXITCODE) { continue }
        return [xml](Get-Content (Join-Path $evidence "$Name.xml") -Raw -Encoding UTF8)
    }
    throw "Fresh UI capture failed: $Name"
}
function Focus([xml]$UI) { return $UI.SelectSingleNode('//node[@focused="true"]') }
function Assert([bool]$Condition, [string]$Message) { if (!$Condition) { throw $Message } }
function TimeSeconds([xml]$UI) {
    $node = $UI.SelectSingleNode('//node[contains(@text," / ")]')
    Assert ($null -ne $node) 'Progress control missing'
    $time = ($node.text -split ' / ')[0] -split ':'
    return [int]$time[0] * 60 + [int]$time[1]
}
& $adb -s $Device shell am force-stop com.reelix.app
& $adb -s $Device shell am start -n com.reelix.app/.debug.PlayerDiagnosticsActivity | Out-Null
$null = ReadUI 'player-initial'
SendKeys @(23)
$before = ReadUI 'player-paused'
Assert ($null -ne $before.SelectSingleNode('//node[@content-desc="播放"]')) 'OK did not pause playback'
$t0 = TimeSeconds $before
SendKeys @(4,22)
$after = ReadUI 'player-hidden-seek'
Assert ((TimeSeconds $after) -eq ($t0 + 10)) 'Hidden-controls RIGHT must seek exactly 10 seconds'
SendKeys @(21)
$left = ReadUI 'player-visible-seek'
Assert ((TimeSeconds $left) -eq $t0) 'Visible-controls LEFT must seek exactly 10 seconds'
$results.hiddenSeek = 'pass'; $results.visibleSeek = 'pass'; $results.confirmPause = 'pass'
SendKeys @(20)
$ui = ReadUI 'player-episode-first'
Assert ((Focus $ui).'content-desc' -eq '第1集') 'Panel must open on playing episode'
SendKeys (@(22) * 40)
$ui = ReadUI 'player-episode-fast40'
$fastFocus = (Focus $ui).'content-desc'
$fastIndex = [int]($fastFocus -replace '\D','')
Assert ($fastIndex -ge 30 -and $fastIndex -le 41) 'Rapid movement must retain focus and cross two page boundaries'
SendKeys @(23,20)
$ui = ReadUI 'player-episode-reopen'
Assert ((Focus $ui).'content-desc' -eq $fastFocus) 'Reopening must focus playing episode'
$results.fast40 = $fastFocus; $results.reopen = $fastFocus
SendKeys @(19,20)
$ui = ReadUI 'player-page-return'
Assert ((Focus $ui).'content-desc' -eq $fastFocus) 'Page-row round trip must preserve episode focus'
SendKeys @(19)
SendKeys (@(22) * 12)
SendKeys @(20)
SendKeys (@(22) * 40)
$ui = ReadUI 'player-episode-last'
Assert ((Focus $ui).'content-desc' -eq '第120集') 'Last episode must retain focus'
SendKeys @(19)
SendKeys (@(21) * 12)
SendKeys @(20)
SendKeys (@(21) * 40)
$ui = ReadUI 'player-episode-start'
Assert ((Focus $ui).'content-desc' -eq '第1集') 'First episode must retain focus'
$results.boundaries = 'pass'
SendKeys @(20)
$ui = ReadUI 'player-speed'
Assert ((Focus $ui).'content-desc' -eq '1.0') 'DOWN must enter selected playback speed'
SendKeys @(22,23)
$ui = ReadUI 'player-speed-change'
Assert ((Focus $ui).'content-desc' -eq '1.25x') 'Speed selection must keep focus'
$results.speed = 'pass'
SendKeys @(20,20,20)
$ui = ReadUI 'player-skip-ready'
Assert ((Focus $ui).'content-desc' -eq '关闭') 'Rapid DOWN must reach selected skip option without losing focus'
SendKeys @(21,21,21,21)
SendKeys @(23)
$null = ReadUI 'player-skip-editing'
SendKeys @(19,23)
$ui = ReadUI 'player-skip-edited'
Assert ($ui.OuterXml.Contains('片头 00:01')) 'Skip editor must still change and save intro duration'
$results.skipEdit = 'pass'
SendKeys @(22,22,23)
$ui = ReadUI 'player-skip-current-intro'
Assert ((Focus $ui).'content-desc' -eq '当前时间设为片头') 'Capturing time must keep focus on the same action'
$intro = $ui.SelectSingleNode('//node[starts-with(@content-desc,"片头 ")]').'content-desc'
Assert ($intro -ne '片头 00:01' -and $intro -ne '片头 00:00') 'Current playback position must replace intro boundary'
SendKeys @(22,23)
$ui = ReadUI 'player-skip-current-outro'
Assert ((Focus $ui).'content-desc' -eq '当前时间设为片尾') 'Capturing outro must keep focus'
$outro = $ui.SelectSingleNode('//node[starts-with(@content-desc,"片尾 ")]').'content-desc'
Assert ($outro -ne '片尾 00:00') 'Current playback position must set outro boundary'
$results.skipCurrentTime = 'pass'
SendKeys @(4)
$ui = ReadUI 'player-panel-closed'
Assert ($null -ne $ui.SelectSingleNode('//node[contains(@text," / ")]')) 'BACK must return to controls'
$results.back = 'pass'
$results | ConvertTo-Json | Set-Content (Join-Path $evidence 'player-emulator-results.json') -Encoding UTF8
$results | ConvertTo-Json
