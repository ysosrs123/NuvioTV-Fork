# Controlled TS entry validation and local reading

Development checkpoint, 5 October 2026. Internal components only. Pause, timeshift,
recording and a production local Media3 timeline are not enabled by this work.

## Structural inspection and evidence

`TsCaptureInspector` independently examines complete MPEG-TS resources. It does not
trust `EXT-X-INDEPENDENT-SEGMENTS`, random-access flags or EXTINF as decode proof.
It requires 188-byte packet alignment, intact continuity counters, no transport
error/scrambling/discontinuity flags, and a single stable program. PAT/PMT sections
must fit single packets, have valid MPEG CRCs, precede media and remain identical.
The PMT must declare exactly AVC and AAC, with PCR on the video PID, without
descriptors. SDT and null payloads are ignored; undeclared elementary PIDs fail.

The intentionally narrow video subset is Baseline AVC with an AUD and one slice
per access unit/PES, in-band SPS/PPS before the first IDR, matching parameter-set
identities and unchanged parameter-set bytes. It rejects slice groups, B/SP/SI
slice types, non-IDR initial video, reordered PTS/DTS, missing timing and uneven
video PTS cadence. Leading SEI is allowed: the controlled encoder emitted it before
AUD. AAC is MPEG-4 AAC-LC ADTS, no CRC/multiple raw blocks, 44.1/48 kHz mono/stereo,
stable headers, complete PES/frame boundaries and coherent audio PTS progression.

Defaults bound a segment to 8 MiB and each assembled PES to 1 MiB. Packet loops,
NAL count and Exp-Golomb parsing are bounded; cancellation is checked during input.
Buffers/transient copies are bounded, but are not a measured device-memory budget.
The caller owns input closure. Returned evidence contains encoded SHA-256,
initialization fingerprint, program/PIDs, sample counts and actual 90 kHz PTS.
33-bit PTS wrap is handled within one segment. Audio is placed on the video epoch.
No wall-clock origin or glass-to-glass delay is inferred. The fixture begins at
video PTS 127920 (1.421333 s) and audio PTS 126000 (1.400 s), even though
capture manifest metadata begins at zero. These clocks must be mapped explicitly
in the future player timeline; audio and video segment boundaries need not coincide.

This is a header inspector, not a full SPS/slice/AAC decoder or a guarantee against
corrupt coded payloads. Last-video duration is inferred from constant sample
cadence. General codec profiles, multi-slice/reordered video, multi-program TS,
separate audio, encryption, fMP4/init maps, ranges and progressive chunk boundaries
remain unsupported here. The foreground player retains its existing capabilities.
The HLS capture source is not yet automatically gated or annotated by this inspector.

## Reproduced final-sample omission and scoped bridge

Three original synthetic 320x180/25 fps segments contain 50 video frames apiece,
plus 95/94/94 AAC frames. FFprobe and strict FFmpeg decoding independently agree.
AM9's platform MediaExtractor returned 49 video samples for the first resource.
The exact shipped Media3 HLS TsExtractor also returned 49; the initial tests failed
and their logs are retained. Inspecting the shipped bytecode showed that PesReader's
EOF `canConsumeSynthesizedEmptyPusi` invokes `parseHeader` after the same scratch
buffer has been overwritten by timestamp-extension bytes. The failed header check
prevents the otherwise-present AVC end-of-input flush.

`LocalTsSegmentExtractor` is a scoped internal bridge over those unchanged AARs.
It uses fresh HLS-mode extraction, retains the video PesReader and hashes/counts
all input. Only after the bytes match an inspection does it submit an empty PUSI
to finalize the complete final video PES. This is idempotent if a future extractor
already moved to its next-header state. There is no artificial media frame,
timestamp, network fetch, tail retry or binary/library upgrade.

The output contract is **tentative until extract returns successfully**: callers
must stage and discard samples on mismatch/failure/cancellation. It is not safe to
render output eagerly and then discover a hash failure. The caller owns the input;
the bridge releases its extractor. A later playback integration must account for
staging memory and pin the immutable inspected local data for the entire operation.
This bridge is not an ExoPlayer MediaSource or a production playback connection.

On AM9, fresh per-segment extraction through the bridge and fresh codecs delivered
every video/audio sample. Video used `c2.amlogic.avc.decoder`; audio used
`c2.android.aac.decoder`. Input and output PTS lists matched. This validates local
decode of these exact fixtures; no visible render, audibility, HDR/UHD, provider or
general codec certification is implied. Exact final outcomes are recorded in
the recorded validation (summarised in IPTV-PROGRESS.md).

## Store concurrency and live reader

Store append now holds a separate writer lock while copying/syncing staged media,
leaving the state monitor available for local snapshots, opens and pins. Publication
rechecks the current pins under the state monitor. A pin acquired while input is
stalled therefore still prevents eviction. Close refuses to release the directory
lock while an append is active. Concurrent writers remain serialized and validate
ordering after acquiring writer ownership. Index sync/promotion and retirement are
still under the state monitor; this is not a claim of zero storage latency.

`CaptureLiveReader` reads complete committed local bytes with explicit DATA,
WAITING, ENDED, EXPIRED, DISCONTINUITY and STOPPED outcomes. It does not implement
InputStream EOF for a temporarily empty live tail. Only COMPLETE means ENDED;
failed/backpressured/closed producers produce STOPPED after draining committed
bytes. It never skips an expired requested sequence or crosses a gap/discontinuity.

The reader starts lazily; an unread starting point can expire. Once reading starts,
it retains the current segment and successors, including while waiting or terminal,
until advancement or explicit close. Pins transfer before the old anchor releases.
Producer state is sampled before the store snapshot so completion cannot be combined
with a stale pre-publication snapshot to manufacture premature EOF. There is no
internal polling loop, provider open, automatic jump or ownership release at EOF.
Reader close remains part of the consumer's confirmed-cleanup responsibility.

The live reader's metadata remains the store's caller/manifest timing. It does not
automatically convert inspected PTS to a decoder-safe seek window. A controlled
Android integration exercises HLS source -> sequential capture -> real store ->
local reader -> inspection -> local extraction bridge -> codecs, using in-process
synthetic responses and no INTERNET permission. It does not exercise concurrent
ExoPlayer rendering while capture is running.

## Remaining implementation gates

1. Bind inspection to committed immutable segments and resource budgets. Persist or
   regenerate evidence while pinned; reject unsupported/corrupt resources before
   publishing any playable timeline, without disrupting unrelated foreground playback.
2. Build a Media3 timeline from verified retained sample windows and initialization
   epochs across segment boundaries, eviction, wrap, stalls and discontinuities.
   Pending seek anchors must work in both directions; explicit return-live and delay
   must use that timeline, not RAM load settings or accumulated manifest durations.
3. Connect the local player and capture through the SAME admission/transport owner.
   Validate paused anchors, recording consumers, cancellation/profile/background exit,
   physical free-space/allocation margins and bounded staging before exposing controls.
4. Continue durable recording/services/leases/schedules, USB/SMB, source/account work,
   multiview/catch-up and the guide/Home/Search/UI requirements in IPTV-HANDOVER.md.

References: [HLS transport initialization and segment rules](https://www.rfc-editor.org/rfc/rfc8216.html#section-3.2),
[MPEG-2 Systems](https://www.itu.int/rec/T-REC-H.222.0),
[AVC syntax](https://www.itu.int/rec/T-REC-H.264), and
[FFmpeg format tooling](https://ffmpeg.org/ffmpeg-formats.html).
Shipped library hashes in the validation report identify the implementation tested.
