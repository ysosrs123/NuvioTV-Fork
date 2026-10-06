"""Reproduce controlled TS entry media; independently probe and decode every committed segment.

No network or provider access. Requires ffmpeg/ffprobe on PATH. Generation is explicit:
  python tools/iptv-device-tests/validate_capture_media.py --generate <empty-directory> --report <json>
Default input is the committed synthetic fixture set. --report is required, to keep measured
evidence separate from source. Different FFmpeg versions may produce different encoded bytes.
"""
from pathlib import Path
import argparse
import hashlib
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]

def run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--generate', type=Path)
    parser.add_argument('--directory', type=Path, default=ROOT / 'app/src/test/resources/iptv-ts')
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    directory = args.directory
    if args.generate:
        directory = args.generate.resolve()
        directory.mkdir(parents=True, exist_ok=True)
        if any(directory.iterdir()):
            raise ValueError('Generation requires an empty directory')
        run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-f', 'lavfi',
             '-i', 'testsrc2=size=320x180:rate=25', '-f', 'lavfi', '-i', 'sine=frequency=440:sample_rate=48000',
             '-t', '6', '-c:v', 'libx264', '-preset', 'ultrafast', '-tune', 'zerolatency',
             '-profile:v', 'baseline', '-pix_fmt', 'yuv420p', '-threads', '1',
             '-x264-params', 'keyint=50:min-keyint=50:scenecut=0:bframes=0:aud=1:repeat-headers=1:slices=1',
             '-c:a', 'aac', '-b:a', '64k', '-ac', '2', '-f', 'hls', '-hls_time', '2', '-hls_list_size', '0',
             '-hls_flags', 'independent_segments', '-hls_segment_filename', str(directory / 'segment%02d.ts'),
             str(directory / 'media.m3u8')])
    results = []
    for path in sorted(directory.glob('segment*.ts')):
        probe = json.loads(run(['ffprobe', '-v', 'error', '-show_streams', '-show_packets', '-of', 'json', str(path)]).stdout)
        streams = probe['streams']
        video = next(s for s in streams if s['codec_type'] == 'video')
        audio = next(s for s in streams if s['codec_type'] == 'audio')
        assert len(streams) == 2 and video['codec_name'] == 'h264' and audio['codec_name'] == 'aac'
        packets = probe['packets']
        vp = [p for p in packets if p['stream_index'] == video['index']]
        ap = [p for p in packets if p['stream_index'] == audio['index']]
        assert len(vp) == 50 and 'K' in vp[0]['flags']
        assert all(p['pts'] == p['dts'] and p['duration'] == 3600 for p in vp)
        assert all(b['pts'] - a['pts'] == 3600 for a, b in zip(vp, vp[1:]))


        decoded = run(['ffmpeg', '-hide_banner', '-loglevel', 'error', '-nostdin', '-xerror', '-err_detect', 'explode',
                       '-i', str(path), '-map', '0:v:0', '-map', '0:a:0', '-f', 'framehash', '-hash', 'sha256', '-'])
        assert not decoded.stderr.strip(), decoded.stderr
        lines = [line for line in decoded.stdout.splitlines() if line and not line.startswith('#')]
        counts = {index: sum(int(line.split(',')[0]) == index for line in lines) for index in (0, 1)}
        assert counts[0] == len(vp) and counts[1] == len(ap), counts
        results.append(dict(file=path.name, sha256=hashlib.sha256(path.read_bytes()).hexdigest(), bytes=path.stat().st_size,
                            video=dict(profile=video['profile'], width=video['width'], height=video['height'],
                                       firstPts=vp[0]['pts'], lastPts=vp[-1]['pts'], step=3600, frames=len(vp)),
                            audio=dict(sampleRate=audio['sample_rate'], channels=audio['channels'],
                                       firstPts=ap[0]['pts'], lastPts=ap[-1]['pts'], frames=len(ap)),
                            strictDecode='PASS', decodedFrames=counts,
                            framehashSha256=hashlib.sha256(decoded.stdout.encode()).hexdigest()))
    assert len(results) == 3
    report = dict(ffmpeg=run(['ffmpeg', '-version']).stdout.splitlines()[0],
                  ffprobe=run(['ffprobe', '-version']).stdout.splitlines()[0], segments=results,
                  scope='Synthetic independent AVC Baseline/AAC-LC TS; no provider, display, audibility or general codec certification')
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(report, indent=2))

if __name__ == '__main__':
    main()
