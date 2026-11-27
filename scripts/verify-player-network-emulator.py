"""Requires player-network-fixture.py running on host loopback port 18765."""
import json
import re
import urllib.request
import runpy
import pathlib
globals().update({k: v for k, v in runpy.run_path(
    str(pathlib.Path(__file__).with_name('verify-engines-emulator.py')), run_name='emulator_helpers').items()
    if not k.startswith('__')})


def traffic():
    pid = adb('shell', 'pidof', 'com.reelix.app').strip()
    return adb('logcat', '-d', f'--pid={pid}', '-s', 'ReelixTraffic:D', 'ReelixPlayer:E', 'AndroidRuntime:E')


def run_network():
    for engine in ['EXO', 'VLC']:
        for name, path in [('mp4', 'movie.mp4'), ('hls', 'hls/index.m3u8'), ('dash', 'dash/manifest.mpd')]:
            key = f'{engine}-{name}'
            launch(engine=engine, uri='http://10.0.2.2:18765/required/' + path, requiredHeaders=True, episodes=1)
            for attempt in range(4):
                tree = ui(f'network-{key}-{attempt}')
                assert '播放失败' not in texts(tree), texts(tree)
                if re.search(r' / (?!00:00)\d+:\d+', texts(tree)):
                    break
            else:
                raise AssertionError('No prepared video: ' + key)
            log = traffic()
            samples = [int(x) for x in re.findall(r'bytesPerSecond=(\d+)', log)]
            assert any(x > 0 for x in samples), key + log
            assert 'FATAL EXCEPTION' not in log
            RESULTS[key] = {'status': 'pass', 'peakBytesPerSecond': max(samples)}
            print(key, RESULTS[key], flush=True)

    launch(uri='http://10.0.2.2:18765/required/movie.mp4', requiredHeaders=True, episodes=1)
    ui('network-cache-replay')
    assert any(int(n) > 0 for n in re.findall(r'cacheBytesRead=(\d+)', traffic())), 'Cached and remote data may be read in one sample; check actual cache bytes rather than assuming a fully cached file'
    RESULTS['exoCache'] = 'pass'
    launch(engine='VLC', uri='http://10.0.2.2:18765/movie.mp4', episodes=1)
    tree = ui('network-vlc-native')
    assert '网速无法统计' in texts(tree), texts(tree)
    assert 'activity=UNAVAILABLE' in traffic()
    RESULTS['vlcNativeUnavailable'] = 'pass'
    launch(networkFixture=True, episodes=1)
    tree = ui('network-local-proxy')
    assert '网速无法统计' in texts(tree), texts(tree)
    RESULTS['thirdPartyLoopbackUnavailable'] = 'pass'
    stats = json.load(urllib.request.urlopen('http://127.0.0.1:18765/stats', timeout=5))
    (OUT / 'network-server-stats.json').write_text(json.dumps(stats, indent=2), encoding='utf-8')
    assert any(x['requiredHeaderPresent'] and '/dash/' in x['path'] and x['bytes'] > 0 for x in stats)
    RESULTS['requiredHeadersAndSegments'] = 'pass'


try:
    run_network()
finally:
    (OUT / 'network-emulator-results.json').write_text(json.dumps(RESULTS, indent=2), encoding='utf-8')
    print(json.dumps(RESULTS, indent=2), flush=True)
