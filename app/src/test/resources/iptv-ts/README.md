# Controlled MPEG-TS entry fixtures

Original synthetic FFmpeg testsrc2 plus 440 Hz sine, created for this repository.
No provider media, credentials or third-party programme content. Three 2-second
320x180/25 fps AVC Constrained Baseline segments with 48 kHz stereo AAC-LC.
Each starts with its own PAT/PMT, SPS/PPS and IDR. Each has 50 video frames;
audio frames are 95, 94 and 94. PTS is transport timing, not wall-clock latency.

Reproduce into a NEW empty directory with
`tools/iptv-device-tests/validate_capture_media.py --generate <directory> --report <json>`.
Run the same script without --generate to independently probe/decode these exact
committed bytes using FFprobe/FFmpeg. See the recorded validation
for hashes, tool version and Android evidence. Encoder versions can change output bytes.

These narrow fixtures are not general codec, HDR/UHD, audibility or provider certification.
