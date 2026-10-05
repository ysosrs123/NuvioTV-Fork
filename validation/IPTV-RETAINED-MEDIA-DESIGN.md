# Retained inspection, sample epochs and seek ownership

Internal development continuation from 43b1dff on codex/iptv, 5 October 2026.
These components supply structural media evidence and seek policy. Production
Media3 playback, pause/timeshift and recording controls remain unconnected.

## Committed-row evidence and bounded input

`CaptureTsInspectionIndex` binds header inspection to the complete `CaptureSegment`
row, including the private UUID filename, and a particular index/store instance.
Acquiring its input and row is atomic under the store monitor. Inspection happens
outside that monitor while the reader pins the file and successors. One inspection
per index runs at a time; its existing segment/PES/parser limits still apply.
The default cache retains at most 256 evidence objects (configurable 1–4096),
holds no long-lived pins and drops entries for retired rows. Evidence is regenerated
after a store/index reopen; it is not a persisted decoder or recovery certificate.

`open(proof)` rejects foreign ownership and exact rows that have expired, then
atomically pins that particular file. `InspectedCaptureInput` verifies byte count
and SHA-256 on every complete read, including `skip`. Bytes stay tentative until
verified EOF; early close/cancellation does not certify them. Hash failure remains
failed. Even verified EOF retains the pin until explicit close. The caller owns
closure and must keep its admission reservations until cleanup is confirmed.
Private committed files are immutable through the store API; unexpected external
replacement is detected when the input is consumed. Cached header evidence alone
must never authorize eager rendering before the complete byte/decode checks.

The cache/input limits bound this component's work. Aggregate viewer memory, sample
staging, physical free-space margins and concurrent owner admission remain separate
integration gates. Multiple inputs are not implicitly admitted by this class.

## Stable PTS epochs

`CaptureSampleTimeline` accepts only index-owned evidence while its row is pinned.
Positions use 90 kHz ticks relative to the epoch's first video sample. Duration
comes from inspected sample count/cadence; manifest times only detect capture gaps.
Audio keeps its real phase, including a negative initial position or a final audio
sample extending beyond the video range. No wall-clock origin/delay is inferred.

Joining requires adjacent sequences, contiguous capture metadata, unchanged capture
continuity and initialization, unchanged video cadence, exact video PTS adjacency,
and audio adjacency within one tick of rounding. A sequence gap, capture gap,
configuration change or media timestamp break starts an explicit new epoch.
33-bit PTS wrap is compared modulo the wrap while published positions stay stable.
Old retained epochs remain distinct; seek policy never silently crosses them.

The default publication budget is 256 windows (configurable 1–4096). Retirement or
budget pruning removes old windows, but the last accepted tail stays available as
metadata to continue the epoch. Even an empty retained snapshot cannot reset the
origin on the next adjacent publication. Reopening a store creates a new timeline
owner/origin; no restart-stable persisted epoch is claimed. Duplicate evidence is
idempotent, changed evidence and rewinding already-pruned history are rejected.
Snapshots describe a moment; an input must still be pinned again at seek commit.

## Pending seek and explicit captured tail

`CaptureSeekController` uses the pending requested position for both left and right
steps until the player acknowledges that exact committed request. Preview holds no
disk pin and may visibly report clamping to a currently published sample range.
Commit either pins the exact requested row or returns EXPIRED; it never clamps or
substitutes another file at commit. Superseded, foreign, cancelled and duplicate
commits return STALE. Late acknowledgements cannot clear newer pending requests.

`CaptureSeekInput` reports the initial segment IDR as decode start, the requested
target and the actual constant-cadence sample at/before that target separately.
The future player must stage/verify, initialize the decoder and discard preroll;
these policies do not issue player commands or implement that decode operation.
The caller closes superseded inputs; changing/cancelling a preview cannot silently
release an input still owned by an asynchronous player operation.

Explicit return-to-captured-tail chooses the newest epoch's latest inspected video
sample. It does not claim wall-clock LIVE, network catch-up or an automatic jump.
An expired pending anchor requires an explicit new seek/cancel/return-tail action.

## Measured checks and remaining work

The new 21 core cases use the committed independent TS fixtures and real file
stores: pin ownership/closure, cancellation/failure, cache limits, replacement hash,
inspection concurrency, actual PTS/audio phase, wrap, eviction and empty snapshots,
capture/sequence/configuration/audio/video/cadence breaks, duplicate evidence,
both-direction pending seeks, late acknowledgement, expiry and captured-tail choice.
The full 171-test core suite passed. Full-app and full JVM outcomes are in the
companion validation report; never infer a completed result from a running build.

Next implement bounded transactional sample staging over the exact shipped Media3
extractor, then an actual retained-media Timeline/MediaPeriod and decode/preroll
path. Connect it through the same governed capture/player owner, validate cleanup,
physical storage margins and measured decoder/memory limits, then enable controls.
Durable recording/services/schedules, USB/SMB and the broader handoff remain pending.

## Transactional staging contract for the next integration

Accept a pinned input and the exact matching timeline window. Keep all extractor
formats/sample payloads private until the unchanged LocalTsSegmentExtractor returns
successfully, the input reaches verified EOF and cancellation is checked again.
Reject unexpected tracks/DRM/crypto, initialization changes, missing initial video
keyframe, sample-count/configuration mismatches and inconsistent PTS. The caller's
admitted budget must bound compressed batch bytes, per-sample/pending bytes, sample
count and initialization data; account for parser/buffer/transient copies before
claiming a total heap budget. Release failed staging state without exposing samples.

Media3 may unwrap relative to the first arriving audio or video PES. Normalize the
batch using its verified first video sample and the window's stable epoch position;
check PTS modulo 33-bit wrap and retain audio's real phase. Do not normalize audio
and video independently or assume manifest zero equals media PTS zero. Exercise
both wrap/order variants and compare extracted counts/PTS to the independent fixture.

An actual MediaPeriod must implement staged input ownership, decoder initialization,
preroll and sample discard, WAITING at a temporary captured tail, explicit epoch
transitions and final producer state. A seek acknowledgement is valid only after
that exact player operation completes; old asynchronous completion cannot release
or acknowledge newer media. Input close, staging memory and decoder/account leases
remain part of governed cleanup. These requirements are not implemented here.
