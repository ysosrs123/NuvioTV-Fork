"""Local-only HLS regression fixture. See HLS-FIXTURES.md; no provider requests."""
from pathlib import Path
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
import argparse, json, time, re

parser = argparse.ArgumentParser()
parser.add_argument("--directory", type=Path, required=True, help="Fixture directory with media/segments.m3u8 and segNNN.ts")
args = parser.parse_args()
root = args.directory.resolve()
assert (root / "media/segments.m3u8").is_file(), "Generate the fixture first"
segments = sorted((root / 'media').glob('seg*.ts'))
durations = [float(x) for x in re.findall(r'#EXTINF:([0-9.]+)', (root / 'media/segments.m3u8').read_text())]
payloads = [p.read_bytes() for p in segments]
offsets = [sum(map(len, payloads[:i])) for i in range(len(payloads))]
combined = b''.join(payloads)
starts = {}
def event(**fields):
    with (root / 'http-events.jsonl').open('a', encoding='utf-8') as out:
        out.write(json.dumps({'time': time.time(), **fields}) + '\n')
class Fixture(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    def log_message(self, *args): pass
    def send(self, payload, mime, status=200, headers=None):
        self.send_response(status)
        self.send_header('Content-Type', mime)
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Connection', 'close')
        for k, v in (headers or {}).items(): self.send_header(k, v)
        self.end_headers(); self.close_connection = True
        try: self.wfile.write(payload); self.wfile.flush()
        except (ConnectionError, OSError): pass
    def do_GET(self):
        path = urlsplit(self.path).path
        mode = (root / 'failure-mode.txt').read_text().strip() if (root / 'failure-mode.txt').exists() else 'single'
        event(path=path, range=self.headers.get('Range'), failureMode=mode)
        if path == '/catalogue.m3u':
            lines = ['#EXTM3U']
            for name, route in [('HLS Sliding', 'sliding/index.m3u8'), ('HLS Byte Ranges', 'ranges/index.m3u8'), ('HLS Failure', 'failure/index.m3u8'), ('HLS Extensionless', 'extensionless/play')]:
                lines += [f'#EXTINF:-1,{name}', f'http://127.0.0.1:18767/{route}']
            self.send(('\n'.join(lines)+'\n').encode(), 'audio/x-mpegurl'); return
        group = path.strip('/').split('/')[0]
        if path.endswith('/index.m3u8') or path == '/extensionless/play':
            if group == 'failure' and mode == 'master':
                self.send(b'#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360\nlow.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=1600000,RESOLUTION=640x360\nhigh.m3u8\n', 'application/vnd.apple.mpegurl'); return
            if group == 'failure':
                self.send(b'#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\nmissing.ts\n', 'application/vnd.apple.mpegurl'); return
            start = starts.setdefault(group, time.monotonic())
            index = min(int((time.monotonic()-start)/2), len(payloads)-6)
            lines = ['#EXTM3U', '#EXT-X-VERSION:4', '#EXT-X-TARGETDURATION:2', f'#EXT-X-MEDIA-SEQUENCE:{index}']
            for i in range(index, index+6):
                lines += [f'#EXTINF:{durations[i]},']
                if group == 'ranges': lines += [f'#EXT-X-BYTERANGE:{len(payloads[i])}@{offsets[i]}', 'combined.ts']
                else: lines += [f'seg{i:03d}.ts']
            self.send(('\n'.join(lines)+'\n').encode(), 'application/vnd.apple.mpegurl'); return
        if path in ('/failure/low.m3u8', '/failure/high.m3u8'):
            target = b'low-missing.ts' if path.endswith('low.m3u8') else b'high-missing.ts'
            self.send(b'#EXTM3U\n#EXT-X-TARGETDURATION:2\n#EXT-X-MEDIA-SEQUENCE:0\n#EXTINF:2,\n' + target + b'\n', 'application/vnd.apple.mpegurl'); return
        if path.endswith('/combined.ts'):
            match = re.fullmatch(r'bytes=(\d+)-(\d*)', self.headers.get('Range',''))
            if not match: self.send(b'range required', 'text/plain', 416); return
            start = int(match[1]); end = int(match[2]) if match[2] else len(combined)-1
            if start < 0 or end >= len(combined) or end < start: self.send(b'invalid range', 'text/plain', 416); return
            self.send(combined[start:end+1], 'video/mp2t', 206, {'Content-Range':f'bytes {start}-{end}/{len(combined)}'}); return
        match = re.fullmatch(r'/(sliding|extensionless)/seg(\d{3})\.ts', path)
        if match and int(match[2]) < len(payloads): self.send(payloads[int(match[2])], 'video/mp2t'); return
        self.send(b'fixture unavailable', 'text/plain', 503)
ThreadingHTTPServer(('127.0.0.1',18767), Fixture).serve_forever()
