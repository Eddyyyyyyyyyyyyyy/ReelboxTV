"""Account-free tests; all commands are pinned to a local emulator, never a TV."""
import json
import pathlib
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parent.parent
ADB = r'D:\AndroidDev\Sdk\platform-tools\adb.exe'
DEVICE = sys.argv[1] if len(sys.argv) > 1 else 'emulator-5554'
if not re.fullmatch(r'emulator-\d+', DEVICE):
    raise SystemExit('Local emulator required')
OUT = ROOT / 'work'
RESULTS = {}


def adb(*args):
    result = subprocess.run([ADB, '-s', DEVICE, *map(str, args)], capture_output=True, encoding='utf-8', errors='replace', timeout=45)
    if result.returncode:
        raise RuntimeError(result.stderr + result.stdout)
    return result.stdout


def launch(**extras):
    adb('shell', 'am', 'force-stop', 'com.reelix.app')
    cmd = ['shell', 'am', 'start', '-n', 'com.reelix.app/.debug.PlayerDiagnosticsActivity']
    for key, value in extras.items():
        flag = '--ez' if isinstance(value, bool) else '--ei' if isinstance(value, int) else '--es'
        cmd += [flag, key, str(value).lower() if isinstance(value, bool) else str(value)]
    adb(*cmd)


def keys(*codes):
    adb('shell', 'input', 'keyevent', *codes)


def ui(name):
    for _ in range(3):
        adb('shell', 'rm', '-f', '/sdcard/reelix-engines.xml')
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/reelix-engines.xml')
            adb('pull', '/sdcard/reelix-engines.xml', OUT / f'{name}.xml')
            return ET.parse(OUT / f'{name}.xml').getroot()
        except (RuntimeError, ET.ParseError):
            continue
    raise AssertionError('No fresh UI: ' + name)


def texts(tree):
    return ' '.join(n.get('text', '') + ' ' + n.get('content-desc', '') for n in tree.iter('node'))


def focused(tree):
    return next((n for n in tree.iter('node') if n.get('focused') == 'true'), None)


def desc(tree):
    node = focused(tree)
    return '' if node is None else node.get('content-desc') or node.get('text', '')


def seconds(tree):
    match = re.search(r'(\d+):(\d+) / ', texts(tree))
    assert match, 'Progress missing'
    return int(match[1]) * 60 + int(match[2])


def menu_find(label, name, direction=20):
    for i in range(12):
        tree = ui(f'{name}-{i}')
        if label in desc(tree):
            return tree
        keys(direction)
    raise AssertionError(f'Menu option {label} unreachable; focus={desc(tree)}')


def run():
    # Real DTS input must initialize FFmpeg audio; appearance of a menu is insufficient.
    launch(uri='file:///data/user/0/com.reelix.app/files/player-dts.mkv')
    tree = ui('engine-ffmpeg')
    assert '播放失败' not in texts(tree), texts(tree)
    pid = adb('shell', 'pidof', 'com.reelix.app').strip()
    log = adb('logcat', '-d', f'--pid={pid}', '-s', 'ReelixDecoder:I')
    assert re.search(r'audio=ffmpeg.*dca', log), log
    RESULTS['ffmpegDts'] = 'pass'

    launch(uri='file:///data/user/0/com.reelix.app/files/player-multiaudio.mp4')
    ui('engine-exo-start')
    keys(23)
    before = ui('engine-before-switch')
    assert '播放' in texts(before)
    position = seconds(before)
    keys(20)
    menu_find('自动选择', 'engine-audio')
    keys(22, 22, 23)
    english = ui('engine-english')
    assert '英语' in desc(english) and focused(english).get('text', '').startswith('✓'), desc(english)
    keys(20)
    menu_find('ExoPlayer', 'engine-switch')
    keys(22, 23)
    chosen = ui('engine-vlc-chosen')
    assert 'VLC' in desc(chosen)
    keys(4)
    restored = ui('engine-vlc-restored')
    assert abs(seconds(restored) - position) <= 1, (position, seconds(restored))
    assert any(n.get('content-desc') == '播放' for n in restored.iter('node')), 'Pause state lost'
    assert '本地播放' in texts(restored) and '缓冲余量未知' in texts(restored)
    keys(22)
    seek = ui('engine-vlc-seek')
    assert abs(seconds(seek) - position - 10) <= 1
    RESULTS['manualEngineSwitchResumePauseSeek'] = 'pass'
    keys(20)
    menu_find('英语', 'engine-vlc-audio')
    vlc_audio = ui('engine-vlc-english')
    assert focused(vlc_audio).get('text', '').startswith('✓'), 'Cross-engine selected audio lost'
    RESULTS['crossEngineAudioRestore'] = 'pass'
    keys(20)
    menu_find('VLC', 'engine-return-exo')
    keys(21, 23, 4)
    back = ui('engine-exo-restored')
    assert abs(seconds(back) - position - 10) <= 1
    assert any(n.get('content-desc') == '播放' for n in back.iter('node'))
    RESULTS['returnExoPausedPosition'] = 'pass'

    launch(screen='detail', episodes=120)
    ui('engine-detail-start')
    keys(20, 20, 22, 22, 22, 22, 22)
    old = ui('engine-detail-episode')
    identity = desc(old)
    assert identity == '第6集', identity
    keys(20)
    button = ui('engine-detail-order')
    assert '正序' in desc(button), desc(button)
    keys(23)
    assert '倒序' in desc(ui('engine-detail-reversed'))
    keys(19)
    assert desc(ui('engine-detail-restored')) == identity
    keys(22)
    assert desc(ui('engine-detail-next')) == '第5集'
    RESULTS['detailReverseFocusNumber'] = 'pass'

    launch(screen='search')
    ui('engine-search-start')
    keys(20, 20, 20)
    group = ui('engine-search-group3')
    assert desc(group).startswith('测试源3'), desc(group)
    assert '源3影片' in texts(group) and '源1影片' not in texts(group), texts(group)
    time.sleep(2)
    refresh = ui('engine-search-async')
    assert desc(refresh).startswith('测试源3'), desc(refresh)
    keys(22)
    assert '源3影片' in desc(ui('engine-search-posters'))
    keys(21, 19)
    previous = ui('engine-search-up')
    assert desc(previous).startswith('测试源2') and '源2影片' in texts(previous), texts(previous)
    RESULTS['searchFocusSelectAsyncRightReturn'] = 'pass'


if __name__ == '__main__':
    try:
        run()
    finally:
        (OUT / 'engines-emulator-results.json').write_text(json.dumps(RESULTS, ensure_ascii=False, indent=2), encoding='utf-8')
        print(json.dumps(RESULTS, ensure_ascii=False, indent=2), flush=True)
