#!/usr/bin/env python3
"""Self-authored test patterns, served through a local Stremio-compatible fixture."""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import shutil
import subprocess
from urllib.parse import unquote, urlparse


def generate(directory):
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        raise SystemExit("ffmpeg is required to generate the synthetic fixtures")
    directory.mkdir(parents=True, exist_ok=True)
    for language, sentence in (("en", "Nuvio validation subtitle"), ("es", "Subtítulo de validación de Nuvio")):
        (directory / f"{language}.srt").write_text(
            f"1\n00:00:00,000 --> 00:02:00,000\n{sentence}\n", encoding="utf-8")
        (directory / f"{language}.vtt").write_text(
            f"WEBVTT\n\n00:00.000 --> 02:00.000\n{sentence}\n", encoding="utf-8")
    for identifier, rate in (("24", "24"), ("5994", "60000/1001")):
        target = directory / f"pattern-{identifier}.mp4"
        if target.exists():
            continue
        subprocess.run([
            ffmpeg, "-hide_banner", "-loglevel", "warning", "-n",
            "-f", "lavfi", "-i", f"testsrc2=size=1280x720:rate={rate}",
            "-f", "lavfi", "-i", "anullsrc=channel_layout=stereo:sample_rate=48000",
            "-i", str(directory / "en.srt"), "-i", str(directory / "es.srt"),
            "-map", "0:v", "-map", "1:a", "-map", "1:a", "-map", "2:s", "-map", "3:s",
            "-c:v", "libx264", "-threads", "2", "-preset", "veryfast", "-crf", "28", "-pix_fmt", "yuv420p",
            "-c:a", "aac", "-b:a", "64k", "-c:s", "mov_text",
            "-metadata:s:a:0", "language=eng", "-metadata:s:a:1", "language=spa",
            "-metadata:s:s:0", "language=eng", "-metadata:s:s:1", "language=spa",
            "-t", "120", "-movflags", "+faststart", str(target)
        ], check=True)
    poster = directory / "pattern.jpg"
    if not poster.exists():
        subprocess.run([ffmpeg, "-hide_banner", "-loglevel", "error", "-n", "-i",
                        str(directory / "pattern-24.mp4"), "-frames:v", "1", str(poster)], check=True)


def serve(directory, port):
    origin = f"http://127.0.0.1:{port}"
    metas = [{"id": f"nv2:{identifier}", "type": "movie", "name": f"Nuvio pattern {rate} fps",
              "poster": origin + "/pattern.jpg", "background": origin + "/pattern.jpg",
              "posterShape": "landscape", "description": "Self-authored synthetic motion pattern. Two silent audio tracks and two subtitle languages. UI/AFR validation only.",
              "runtime": "2 min", "genres": ["Validation"], "releaseInfo": "2026"}
             for identifier, rate in (("24", "24"), ("5994", "59.94"))]
    subtitles = [{"id": f"nv2-{lang}", "url": f"{origin}/{lang}.vtt", "lang": lang}
                 for lang in ("eng", "spa")]
    subtitles[0]["url"] = origin + "/en.vtt"
    subtitles[1]["url"] = origin + "/es.vtt"
    files = {name: directory / name for name in ("pattern-24.mp4", "pattern-5994.mp4", "pattern.jpg", "en.vtt", "es.vtt")}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.respond(False)

        def do_HEAD(self):
            self.respond(True)

        def respond(self, head):
            path = unquote(urlparse(self.path).path).strip("/")
            data = None
            if path == "manifest.json":
                data = {"id": "org.nuvio.validation.synthetic", "name": "Nuvio synthetic validation",
                        "version": "1.0.0", "description": "Local self-authored patterns for UI and AFR checks.",
                        "resources": ["catalog", "meta", "stream", "subtitles"], "types": ["movie"],
                        "idPrefixes": ["nv2:"], "catalogs": [{"type": "movie", "id": "nv2-patterns", "name": "Validation patterns"}]}
            elif path.startswith("catalog/movie/nv2-patterns"):
                data = {"metas": metas}
            elif path.startswith("meta/movie/"):
                identifier = path.removeprefix("meta/movie/").removesuffix(".json")
                meta = next((item for item in metas if item["id"] == identifier), None)
                data = {"meta": meta}
            elif path.startswith("stream/movie/nv2:"):
                identifier = path.removeprefix("stream/movie/nv2:").removesuffix(".json")
                if identifier in ("24", "5994"):
                    data = {"streams": [{"name": "Local test pattern", "title": "H.264 · silent stereo · EN/ES subtitles",
                                          "url": f"{origin}/pattern-{identifier}.mp4", "subtitles": subtitles}]}
            elif path.startswith("subtitles/movie/nv2:"):
                data = {"subtitles": subtitles}
            if data is not None:
                body = json.dumps(data).encode("utf-8")
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                if not head:
                    self.wfile.write(body)
                return
            file = files.get(path)
            if file is None or not file.is_file():
                self.send_error(404)
                return
            size = file.stat().st_size
            start, end = 0, size - 1
            range_header = self.headers.get("Range")
            if range_header:
                match = re.fullmatch(r"bytes=(\d*)-(\d*)", range_header)
                if match and match[1]:
                    start = int(match[1])
                    end = min(int(match[2]) if match[2] else end, end)
                elif match and match[2] and int(match[2]) > 0:
                    start = max(0, size - int(match[2]))
                else:
                    start = size
                if start > end:
                    self.send_response(416)
                    self.send_header("Content-Range", f"bytes */{size}")
                    self.send_header("Content-Length", "0")
                    self.end_headers()
                    return
            self.send_response(206 if range_header else 200)
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Content-Type", {".mp4": "video/mp4", ".jpg": "image/jpeg", ".vtt": "text/vtt"}[file.suffix])
            self.send_header("Content-Length", str(end - start + 1))
            if range_header:
                self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
            self.end_headers()
            if not head:
                try:
                    with file.open("rb") as source:
                        source.seek(start)
                        remaining = end - start + 1
                        while remaining:
                            chunk = source.read(min(65536, remaining))
                            if not chunk:
                                break
                            self.wfile.write(chunk)
                            remaining -= len(chunk)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # Normal when the player seeks or cancels a source.

    print(f"Use adb reverse tcp:{port} tcp:{port}; install {origin}/manifest.json in the isolated app", flush=True)
    ThreadingHTTPServer(("127.0.0.1", port), Handler).serve_forever()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("generate", "serve"))
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--port", type=int, default=8765)
    arguments = parser.parse_args()
    if arguments.command == "generate":
        generate(arguments.directory)
    else:
        serve(arguments.directory, arguments.port)
