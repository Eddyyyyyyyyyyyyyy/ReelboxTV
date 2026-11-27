"""Host-loopback-only HTTP/Range/manifest fixture, exposed to the emulator via 10.0.2.2."""
import http.server
import json
import pathlib
import re
import threading
import time
import urllib.parse

ROOT = pathlib.Path(__file__).resolve().parent.parent / 'work' / 'network-fixture'
STATS = []
LOCK = threading.Lock()


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_HEAD(self):
        self.respond(True)

    def do_GET(self):
        self.respond(False)

    def respond(self, head):
        raw = urllib.parse.urlsplit(self.path).path
        if raw == '/stats':
            with LOCK:
                data = json.dumps(STATS).encode()
            self.send_response(200)
            self.send_header('Content-Length', len(data))
            self.end_headers()
            if not head:
                self.wfile.write(data)
            return
        required = raw.startswith('/required/')
        valid = self.headers.get('Cookie') == 'fixture=ok'
        if required and not valid:
            self.send_error(403)
            return
        relative = raw.removeprefix('/required/').lstrip('/') if required else raw.lstrip('/')
        file = (ROOT / urllib.parse.unquote(relative)).resolve()
        if not file.is_relative_to(ROOT.resolve()) or not file.is_file():
            self.send_error(404)
            return
        length = file.stat().st_size
        match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range', ''))
        start, end = (int(match[1]), int(match[2]) if match[2] else length - 1) if match else (0, length - 1)
        end = min(end, length - 1)
        if start > end:
            self.send_response(416)
            self.send_header('Content-Range', f'bytes */{length}')
            self.end_headers()
            return
        self.send_response(206 if match else 200)
        types = {'.mp4': 'video/mp4', '.m3u8': 'application/vnd.apple.mpegurl', '.mpd': 'application/dash+xml', '.ts': 'video/mp2t'}
        self.send_header('Content-Type', types.get(file.suffix, 'application/octet-stream'))
        self.send_header('Accept-Ranges', 'bytes')
        self.send_header('Content-Length', end - start + 1)
        if match:
            self.send_header('Content-Range', f'bytes {start}-{end}/{length}')
        self.end_headers()
        if head:
            return
        served = 0
        try:
            with file.open('rb') as stream:
                stream.seek(start)
                while served <= end - start:
                    chunk = stream.read(min(8192, end - start + 1 - served))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    self.wfile.flush()
                    served += len(chunk)
                    time.sleep(0.04)
        except (BrokenPipeError, ConnectionResetError):
            pass
        finally:
            with LOCK:
                STATS.append({'path': raw, 'requiredHeaderPresent': valid, 'bytes': served, 'start': start})


http.server.ThreadingHTTPServer(('127.0.0.1', 18765), Handler).serve_forever()
