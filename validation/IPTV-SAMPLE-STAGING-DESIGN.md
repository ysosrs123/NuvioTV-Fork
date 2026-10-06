# Transactional sample staging and Media3 metadata

Development continuation from 25395a2, 6 October 2026. Internal implementation;
production playback and capture controls remain unconnected.

LocalCaptureSampleStager accepts the exact pinned input/window proof and keeps all
extractor output private until length/hash verification, verified EOF, track/format,
count/keyframe/clock checks and final cancellation succeed. Encoded sample arrays
are private; callers copy them to their own buffer. Returned sample lists are
unmodifiable. Input and its pin remain caller-owned even on failure or verified EOF.

Caller-supplied limits bound total incoming compressed/init bytes, per-track pending
and per-sample bytes, sample counts, initialization data and video dimensions. The
compiled fixtures use 2 MiB batch bytes, 512 KiB per sample/pending track, 4096
samples per track, 64 KiB initialization and 1920x1080 pixels. These are logical
component caps, not a measured aggregate heap/decoder reservation. Parser/buffer
copies, object overhead and codec memory require the later admission integration.

Exactly AVC/AAC tracks are supported. Unexpected tracks, DRM/crypto, format changes,
missing initial keyframe or inconsistent sample counts/PTS fail staging. The exact
shipped DefaultTsPayloadReaderFactory has an empty default closed-caption list;
no bundled AAR was upgraded or patched. This narrow capture path does not add
subtitle/CC support or claim general coded-payload validity.

CaptureExtractedSampleTiming validates the raw first PTS modulo the 33-bit wrap,
constant video cadence and coherent AAC timestamps. It uses one shared first-video
reference; audio retains its phase, including negative preroll. It accepts the
ADTS reader's bounded per-frame microsecond rounding and PES anchor rounding.
Overflow and the Long.MIN_VALUE absolute-value edge fail through bounded signed
comparisons. The 44.1 kHz cases exercise arithmetic only; coded fixtures remain 48 kHz.

CaptureMedia3TimelineFactory builds actual Media3 Window/Period metadata from staged
batches whose exact windows are still retained. It refuses staged gaps within one
epoch, keeps period identity/absolute positions across eviction and empty snapshots,
includes the audio tail in period duration and caps input batches at 4096. Each
epoch has a separate window/period. Automatic epoch navigation is disabled; future
player policy must explicitly own decoder transitions. Projection never invents
future captured samples. There is no wall-clock live configuration or LIVE claim.
The factory holds metadata/identity only; it does not own sample bytes or input pins.

JVM timeline fixtures use synthetic completed-batch metadata to check the actual
Java Window/Period fields, identities, offsets and navigation policy. They do not
run the Android TS reader: real SparseArray behavior is required. Six Android
fixtures cover full extraction/sample counts, both first-track orders at wrap,
budgets, cancellation/pin ownership, replacement hash and wrong-proof rejection.
AM9 was observed Asleep after bounded reconnect/power query. Device installation
and execution are deferred while the device is unavailable overnight.
Never present compiled fixtures as an executed extraction/decoder result.

Next, run the Android extraction fixtures when the authorised device is awake,
then check staged payload/normalized PTS through headless codecs. Implement the
actual MediaSource/MediaPeriod loader, waiting/final/error states, preroll and
sample discard, governed capture/player admission and confirmed cleanup. Physical
storage margins and durable recording/services/schedules remain separate gates.
Keep controls disabled until those checks pass. Consult the companion report for
actual final compile/JVM/harness outcomes.
