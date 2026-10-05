# Controlled HLS playback checks

Run only against the isolated Nuvio IPTV Prototype and an explicitly authorised device. This local server uses generated video and silent audio. It binds to `127.0.0.1:18767` and makes no external requests. Stop playback/server and remove only this test's reverse afterwards. Preserve existing apps, accounts, settings and reverses.

Generate a 90-second H.264/AAC fixture in an empty scratch directory (replace `SCRATCH` with its path):

```text
ffmpeg -f lavfi -i testsrc2=size=640x360:rate=25 -f lavfi -i anullsrc=r=48000:cl=stereo -t 90 -c:v libx264 -preset veryfast -g 50 -keyint_min 50 -sc_threshold 0 -c:a aac -f hls -hls_time 2 -hls_list_size 0 -hls_segment_filename SCRATCH/media/seg%03d.ts SCRATCH/media/segments.m3u8
python tools/iptv-device-tests/hls_fixture.py --directory SCRATCH
adb -s AUTHORISED_SERIAL reverse tcp:18767 tcp:18767
```

Create `SCRATCH/media` before generation. Add the M3U source `http://127.0.0.1:18767/catalogue.m3u` using the prototype's native source form, refresh it, then open Live TV and choose that source. Adding/refreshing/focusing must not request media. The server appends request paths and ranges to `SCRATCH/http-events.jsonl`.

- **HLS Byte Ranges:** six-segment sliding window referring to explicit byte ranges in one TS object. Verify rendered video, playlist reloads without Range and segment requests with exact ranges.
- **HLS Sliding:** six-segment moving window with separate TS objects. Verify rendered video and replacement of the range channel; no old-channel requests after the switch.
- **HLS Extensionless:** same playlist format at `/extensionless/play`. Auto cannot identify this URL as HLS. Choose Stream format → HLS, then explicitly activate the channel. Saving the choice must not request media. Verify video, cold-restart persistence and no autoplay.
- **HLS Failure:** default single playlist refers to a segment returning 503. Verify one failed segment request, a user-visible error and no background retry. Write `master` to `SCRATCH/failure-mode.txt` for a master playlist with two failing renditions; verify the second rendition is not opened after the first fails. Delete that file or write `single` to restore the simple case.

The moving window stops advancing after the generated material is exhausted. Restart the server to reset its timeline before a new long playback check; the retained request log distinguishes modes. A playlist may be fetched twice during initial HLS preparation. That is distinct from retrying a failed segment or opening a fallback rendition.

These checks do not certify adaptive bitrate quality, separate audio, encrypted HLS/DRM, LL-HLS, fMP4, redirects, real providers, HDR, audible output or AFR. Use additional fixtures for those paths before claiming support. HTTP traces alone do not prove decoded video; inspect the visible player too.
