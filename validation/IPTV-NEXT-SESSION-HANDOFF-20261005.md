# Nuvio IPTV — fresh-session handoff, 5 October 2026

## Overnight authorization, 5–6 October 2026

The user paused the build, then explicitly authorized resuming and continuing all
IPTV work while asleep, checking back tomorrow. The prior Gradle JVM-test run was
intentionally stopped (not a regression). The resumed final annotated full app
compile passed in 9m18s and the full IPTV JVM suite passed: 196 tests,
zero failures/errors/skips. The final annotated isolated Android harness also builds.
The earlier 214-test Android run precedes only two UnstableApi annotations; no
further device run/install was needed. Exact outcomes/hashes are in the evidence report.

A NEW temporary heartbeat `nuvio-iptv-overnight-continuation` is active in this chat
(hourly, finite overnight schedule). Finish the current bounded operation and pause
it by 08:00 Australia/Brisbane on 6 October, or when the user returns/changes the
plan. The two OLD automations remain paused and must not be resumed. This new
user authorization supersedes only the earlier no-new-automation restriction.
No new subagents or dependency-upgrade work were authorized. Prefer controlled
host/headless checks overnight; do not wake the TV/AVR or change device settings.
The saved project now opens E:/Codex/NuvioTV/work/NuvioTV-Fork, still the WRONG
checkout for IPTV. All IPTV source work stays in its separate sibling/branch.

## Growing epoch-period checkpoint - current validated continuation

From clean 34a4937, added actual cached growing MediaPeriod/SampleStream with one
exclusive reader borrow. Live waiting yields NOTHING, only exact COMPLETE yields
EOS; growth keeps cursors and actual epoch PTS. Explicit consumed-prefix transfer
requires selected tracks to finish those rows, keeps one owned row and closes
inputs/refills off-thread. UID/eviction offsets stay stable. Period holds block
batch release and confirmed reader/runtime closure; independent recorder survives.
Player continueLoading cannot start automatic local tail polling. Period closure
confirms its own borrow only; parent must separately confirm renderer shutdown.

Final app compile passed in 454s, all 307 IPTV JVM tests in 36 suites passed with
zero failures/errors/skips (8.453s test / 406s build), and final headless harness
builds passed in 49s. Core207 passed at the unchanged preceding checkpoint and
those core cases passed again within the full JVM suite; no separate core rerun
is claimed here. Nine new period cases use real ownership/files/inspection/queue /
reader/runtime with SYNTHETIC encoded payloads. Two actual-stager Android growth /
period/complete cases compile only. All commands completed. Exact source/component /
APK/log/XML hashes and superseded app461s/harness49s outcomes are saved in
IPTV-EPOCH-PERIOD-VALIDATION-20261006.json; read its design first.

Next: actual MediaSource/Looper callback binding to cached reader/period while
preserving WAITING and explicit boundaries, confirmation of source/player/renderer
closure and exact pending seek/render acknowledgement. Device normalized audio /
offset/preroll, measured aggregate memory/physical margins, durable storage /
recording/services/schedules, providers/grouping and remaining UX scope below
remain required. No device/provider operation, component upgrade or enabled control.
Temporary automation must finish bounded work/checkpoint/pause by 08:00 Brisbane;
both older jobs remain paused.

## Governed incremental-reader checkpoint - historical validated continuation

From clean 0b82357, added an actual persistent OwnedCaptureConsumer over the bounded
sample queue. One IO worker and a metadata observer coalesce explicit/committed
capture hints, cache immutable batches/Media3 timelines, fence delivered snapshots
and retain pins/charges through failed release and timed-out cleanup. Mandatory
encoded-memory floors are checked before transport/consumer start; they do not
certify aggregate transient/native/decoder memory. Read IPTV-INCREMENTAL-READER-DESIGN.md
and its exact frozen-source/APK/log/XML report before historical milestones.

Final full app compile passed in 457s; 207 core tests passed in 2.179s; all 298 IPTV
JVM tests in 35 suites passed with zero failures/errors/skips (8.251s test / 414s
build); final headless Android harness builds passed in 62s. All build commands
completed. Ten new reader JVM fixtures use actual file/queue/runtime/transport
paths with SYNTHETIC encoded batches. Two actual-stager growth/timeline/epoch
Android fixtures compile only; no device/provider operation or control enablement.

Next bounded work: actual dynamic MediaSource/epoch MediaPeriod/sample-stream
binding to the cached reader. Keep playback-thread access free of store/queue IO,
WAITING distinct from finite EOS, stable epoch/eviction offsets, explicit terminal /
expiry/boundary policy and borrower shutdown before reader release. Admitted
player/renderer ownership, normalized negative audio/preroll/device seek/render
acknowledgement and measured budgets remain gates. Durable storage/providers/UX
scope below remains authorized but unfinished. Old automations remain paused;
temporary overnight continuation must finish/checkpoint/pause by 08:00 Brisbane.

## Incremental sample-load checkpoint — historical continuation

From clean a960696, added actual incremental inspected-sample cursor and bounded
transactional TS-staging queue. Read IPTV-SAMPLE-LOAD-DESIGN.md and the matching
validation report first. Final full app compile passed in 448s; 204 core tests
passed in 2.186s; all 285 IPTV JVM tests in 34 suites passed with zero failures,
errors or skips (7.730s test / 384s build); final current-fixture harness builds
passed in 31s. Exact source/component/APK/log and new-suite XML hashes are saved.
No build remains running from this phase; no unrelated processes stopped.

Cursor inspection pins exact committed rows, publishes stable PTS windows and
loads only exact successors. WAITING is distinct from ENDED (only COMPLETE),
STOPPED, CAPACITY, EXPIRED, DISCONTINUITY, FAILED and CANCELLED. Completed ticket
pins remain until explicit release; the waiting/terminal anchor remains until
successor transfer or close. Queue next worst-case encoded batch/slot caps apply
before opens. Cancellation/failure publishes nothing; uncertain release/close
retains handles and arrays/charges. Reentrant mutation/loading/closure is fenced.
Thirteen core cases inspect original TS fixtures; seven JVM queue cases use
SYNTHETIC compressed batches on real verified pins. Their budgets are logical,
not measured aggregate heap/decoder memory. Neither completion acknowledges seeks.

Three real-stager Android growing-row/metadata, epoch-boundary and cancellation
fixtures compile only, and prior staging/period/reader/probe cases remain unexecuted.
The final cancellation fixture explicitly enters actual TS staging. Earlier 203-
core / 459s app / 63s harness passes precede reentry hardening; the 54s hardened
harness precedes only that Android fixture amendment. All superseded outcomes /
source hashes and distinct logs are retained; final source/APK evidence governs.
No device/provider command, installation, power/CEC/account/settings/recording
operation, component upgrade or control enablement occurred. Target manifest has
no permissions/activities. At 04:43 Brisbane the temporary automation was ACTIVE
and both older automations PAUSED.

Next: bind this incremental queue to a governed asynchronous owner/refresh and
actual dynamic MediaSource/epoch MediaPeriod/sample delivery. Preserve WAITING
rather than publishing finite EOS for a running capture; handle explicit epoch
navigation/stopped/errors/expiry. Then connect admitted real player/renderers,
offsets/preroll/normalized audio and exact seek/render acknowledgement with measured
aggregate memory/storage and confirmed cleanup. The queue is blocking LOCAL IO,
not an actual source/player; decoder borrowers must stop before pin/batch release.
Remaining durable recording/services/schedules/internal/USB/SMB, Stalker/automatic
Xtream EPG/grouping and multiview/guide/Home/Search/Original/V2 scope still stands.
Keep controls disabled. Reserve the final overnight wake for checkpoint/summary
and pause; explicitly pause the temporary automation by 08:00 Brisbane, without
relying on a wake after its finite count expires. Older automations stay paused.

## Pinned-reader checkpoint — historical continuation

From clean 6842df1, added actual asynchronous OwnedCaptureConsumer staging an exact
committed/pinned seek into the finite period, identity/cancellation fences and
retryable worker/cleanup closure. Read IPTV-PINNED-READER-DESIGN.md and its matching
validation report first. Final full app compile passed in 545s; 191 core tests
passed in 1.984s; all 265 IPTV JVM tests (32 suites) passed with zero failures,
errors or skips (7.011s test / 391s build). Final current-source harness builds
passed in 33s; initial 54s result precedes only explicit Unit fixture return types.
Exact source/component/APK/log hashes and the twelve-test reader XML hash are saved.
No build remains running from this phase; unrelated checkout JVM work was untouched.

Twelve new JVM fixtures use real committed pins, inspection, seeks and runtime,
with SYNTHETIC encoded payloads. Verified cancellation before/after transfer, stale
and expired loads, blocked sole worker/closer timeouts and failed cleanup retention.
SharedCaptureRuntime integration preserves reader memory/pins and an independent
existing recorder until confirmed close; this logical fixture budget and mock
recorder are not measured production memory or durable recording certification.
The ready handoff is borrowed, never acknowledges a seek and opens no decoder.
A future player must fence its commands and confirm renderer shutdown before
reader closure; this reader cannot confirm decoder/player closure.

Two new real-stager Android reader cases compile but remain unexecuted, as do
previous staging/period/probe cases. No device/provider commands or installations
this phase; the prior targeted power query observed AM9 Asleep. Target manifest
has no permissions or activities. No library/component versions changed; no
capture controls enabled. Both older automations were checked PAUSED at 03:21
Brisbane; the temporary overnight heartbeat remains ACTIVE to its cutoff.

Next: actual dynamic MediaSource/epoch-period loading with WAITING vs complete,
stopped/error/expiry and explicit epoch transitions, then admitted real player /
renderer ownership, offsets/preroll and exact seek/render acknowledgement. Maintain
measured aggregate memory/storage/device gates and remaining authorized handoff
scope. The finite reader/period is NOT live-tail EOF. Finish the current bounded
operation and pause the temporary automation by 08:00 Brisbane; do not rely on a
wake after its finite count expires. Reserve the last overnight wake for checkpoint,
concise morning summary and pause rather than a new lengthy full build.

## Pinned-period checkpoint — historical continuation

Continued from clean ae0c08bb629d4a8f4df6ec4aea13c917de68733e. Read
IPTV-PINNED-PERIOD-DESIGN.md and IPTV-PINNED-PERIOD-VALIDATION-20261006.json first.
Added an actual finite ONE-segment MediaPeriod/SampleStream, exact verified open
pin transfer, thread/selection fencing, format/peek/omit/EOF reads, real-frame seek
positions and full initial-IDR/audio preroll, with retryable confirmed pin closure.
Final full app compile passed in 503s; all 191 core tests passed in 1.868s;
253 IPTV JVM tests in 31 suites passed with zero failures/errors/skips (6.487s
test time, 370s build). Final current-source Android harness builds passed in 36s.
Exact source, unchanged shipped library, APK and retained log hashes are recorded.
Merged headless target manifest contains no permissions or activities. No controls
enabled; no dependency/component upgrades. No build remains running from this phase.

Targeted AM9 get-state/read-only power query reported Asleep. No connect/install,
wake/power/CEC/settings/account/recording/provider operation occurred. Two new
Android period fixtures remain unexecuted, as do prior staging/probe fixtures.
Nine JVM period cases use synthetic encoded payloads though file pins/verification,
Java Media3 APIs and retryable closure are real. The shipped renderer bytecode uses
output PTS vs reset position for decode-only classification; runtime preroll,
negative-audio/offset behavior is still unvalidated. This is a finite preloaded
period, not a live/epoch loader or MediaSource.
Next: dynamic loading and waiting/error/expiry/epoch policy, an actual owned player
consumer and aggregate admission/cleanup/device gates. Do not use finite EOF to
terminate a running capture; do not acknowledge seeks from preparation/sample
reads. Keep the remaining full handoff scope and 08:00 Brisbane cutoff.

## Physical-storage checkpoint — historical continuation

Continued from 460641888181b05cd43ddb058c9c6380785db0fe. Read
IPTV-STORAGE-FENCE-DESIGN.md and IPTV-STORAGE-FENCE-VALIDATION-20261006.json first.
Added usable-space/allocation-unit/volume observations, explicit caller margins,
worst-case file/index overhead and repeated guarded writes. SharedCaptureRuntime
now refuses unguarded/under-reserved pipelines before transport or consumer start.
Storage rejection stops capture without retry and preserves last-good rows/pins,
pending sequence and existing recording consumers. Uncertain closure retains its
infrastructure lease. Final full app compile, 191 core tests, all 244 IPTV JVM tests
(30 suites, zero failures/errors/skips) and final Android harness builds pass.
Exact source/APK hashes, durations and corrected host FileStore/Win32 readings are
in the report. This JDK reports sector size as blockSize; the host fixture now
queries actual Windows allocation clusters. Initial sector-based evidence was
preserved and superseded. Only the host fixture changed after the app/harness
builds; production/core/Android sources remain identical to those passed builds.

The executed host probe used a small task temporary spool; it does not certify AM9
capacity or allocation behavior. Two Android statvfs fixtures COMPILE but remain
UNEXECUTED, as do the previous six sample-staging Android fixtures. No device
commands, installs, wake/power/CEC/settings/provider/account/recording operations
occurred here. The previous checkpoint observed AM9 Asleep. This is a fresh-space
observation fence, not an OS quota/preallocation, universal production margin,
USB/SMB or power-loss durability certificate. Legacy unguarded direct byte fixtures
remain compatible but governed runtime sharing refuses them. Keep controls disabled.

Next independent implementation: actual Media3 sample streams/MediaPeriod/loading
states and explicit seek preroll/discard/acknowledgement; connect owned consumers
through shared capture admission and confirmed cleanup with measured aggregate
staging/decoder budgets. Execute new Android staging/probe/normalized-PTS codec
fixtures only when the authorised device is awake. Durable record-now/services/
schedules/storage adapters, source/account grouping, Stalker/automatic Xtream EPG,
multiview/catch-up and Nuvio guide/Home/Search/Original/V2 scope remain outstanding.
No dependency versions changed. Never overwrite current source with staging copies.

## Sample-staging checkpoint — historical continuation

Continued from 25395a2a36cf8d8d1ab0e8756886542644e67b46. Read
IPTV-SAMPLE-STAGING-DESIGN.md and IPTV-SAMPLE-STAGING-VALIDATION-20261006.json first.
Added bounded transactional compressed-sample staging, one shared extracted PTS
clock/normalization and actual Media3 Window/Period metadata over staged retained
batches. Final stable-source full app compile, 178 core tests, all 230 IPTV JVM
tests (28 suites, zero failures/errors/skips) and final Android harness builds pass.
Exact durations, source/APK hashes and logs are in the report. The initial compile
was superseded because hardening occurred during it. No MediaSource/MediaPeriod
loader, renderer commands, preroll or production sharing is connected yet.

AM9 192.168.10.60:5555 required bounded reconnect; read-only dumpsys power reported
Asleep. No wake/settings/CEC operation or installation occurred. Six new Android
extraction fixtures COMPILE but remain UNEXECUTED. JVM SparseArray stubs cannot
validate the TS reader. Six JVM timeline cases use synthetic batch metadata with
actual Java Window/Period fields; the 44.1 kHz cases exercise arithmetic only.
The previous 214-test Android decode result applies to its earlier checkpoint.
Keep controls disabled. Next: executed staging/normalized-PTS codec gates when the
authorised device is awake; actual MediaSource/MediaPeriod loading, seek preroll/
discard and acknowledgements; governed capture/player admission/confirmed cleanup;
physical storage margins, then the remaining handoff scope. Never replace current
files with historical staging copies. No dependency versions changed.

## Retained-media checkpoint — historical continuation

Continued from clean 43b1dff41e72638777b4e2476494751f15228101 on codex/iptv.
Read IPTV-RETAINED-MEDIA-DESIGN.md and its validation report first. Added ephemeral
committed-row inspection ownership, bounded cache, pinned length/hash-verifying
inputs, stable PTS/audio epochs across wrap/eviction, and both-direction pending
seek/explicit captured-tail policy. The 171-test core suite passed (21 new cases).
Full app compile passed (8m43s), all 217 IPTV JVM tests passed (26 suites, zero
failures/errors/skips), and the Android harness builds (70s). No device commands
or installation were performed; no new Android execution result is claimed.
These are core policies; production Media3 Timeline/MediaPeriod and player commands
remain unconnected. Keep controls disabled. Next: bounded transactional extracted
sample staging, actual Media3 period/timeline and preroll, governed sharing/cleanup,
physical storage margins, then broader pause/recording and main handoff scope.
Do not replace these current source files with the earlier staging copies.

## Prior TS entry checkpoint — historical status

Work continued from clean 3ae88829851f23f1a6413e77db88965ebc095ebc on codex/iptv in
E:/Codex/NuvioTV/work/NuvioTV-IPTV. Read `IPTV-TS-ENTRY-DESIGN.md` and
`IPTV-TS-ENTRY-VALIDATION-20261005.json` before the older capture reports. The old
statements that no actual TS decode/local live reader exists and that append holds
the monitor through source reads are now historical; production playback remains
unconnected and all larger user requirements/constraints below still apply.

Added bounded TS header inspection (single-program Baseline AVC/AAC with PAT/PMT,
SPS/PPS/IDR and PTS checks), explicit-state CaptureLiveReader and separate store
writer ownership. The inspector does not fully validate coded slice/AAC payloads.
It is not yet attached to persisted capture rows or used to publish seek windows.

AM9 platform MediaExtractor and the exact shipped Media3 HLS extractor each omitted
the final video sample (49/50). Shipped PesReader reparses header scratch already
overwritten by timestamp bytes at EOF. `LocalTsSegmentExtractor` restores the final
AVC sample only after length/hash match a complete inspected resource. Its output
is TENTATIVE until extraction succeeds; future users must stage/discard on failure.
This is an internal extraction bridge, not an ExoPlayer MediaSource. Do not patch or
upgrade the bundled AARs as a shortcut; component upgrades remain separate work.

150 core JVM tests and 214 Android tests passed; FFmpeg independently decoded all
three original synthetic segments. AM9 decoded 50 video and 95/94/94 audio frames
per segment, plus 150 video/283 audio frames through the HLS capture -> store ->
local reader -> bridge -> codec integration. Check the latest evidence report for
full-app/full-JVM results (196 JVM tests passed); old 176/190 counts describe the HLS baseline.

Temporary test packages were removed; prototype unchanged. Device stayed observed
Awake without task wake/power changes. Final reverse list was EMPTY; this task made
no reverse changes. Do not assume the previously observed reverse8765 still exists.
No fixture servers, provider requests, subagents or goals were started. The new
temporary overnight heartbeat was created after these device checks, as above.

Next: bind inspection to pinned immutable committed media and budgets; derive a
Media3 retained-sample timeline across PTS wraps/codec epochs/eviction; implement
both-direction pending seek and explicit return-live; then governed capture/player
sharing, physical disk margins, pause/recording and the remaining full scope below.
Keep the controls disabled until these gates are validated. See latest design.


## Start here

The user explicitly requested a fresh session to continue implementation, a complete handoff and updated comprehensive draft release notes. Continue actual implementation after reading this document; do not stop at a plan. The full IPTV scope remains unfinished. Dependency/component upgrades were handed to another agent and are outside this continuation.

**Authoritative checkout:** `E:/Codex/NuvioTV/work/NuvioTV-IPTV`, **branch `codex/iptv`**. Latest implementation: the retained-media checkpoint described above, following `43b1dff` (TS inspection/local reader), `3ae8882` (handoff), `eaa43e5` (bounded HLS capture), `4c3e0b0` (shared capture) and `27f78ea` (earlier handover). Read the current Git log for the checkpoint hash. Verify HEAD, branch and status before editing; keep IPTV on its separate branch. Published base: nt4.1, build 1458, `574a2d41257ccc667828f54ba0c6ef40d9784984`.

The saved Codex project points to the ORIGINAL sibling `NuvioTV-Fork`, not this implementation checkout. Its equivalent saved C: project is `C:/Users/PWR/Documents/Codex/2026-09-13/prior-conversation-with-codex-conversation-role/work/NuvioTV-Fork`; the E: work folder is the operational path used here. The original fork was dirty on `feature/nuvio-v2-ui` with a broken Codex checkpoint ref. Do not reset, repair, stash, commit or implement IPTV there. Only ignored task scratch under its `captures/` was added. Other upgrade work may now be happening there; do not overwrite it.

Read next: `IPTV-HLS-CAPTURE-DESIGN.md`, `IPTV-HLS-CAPTURE-VALIDATION-20261005.json`, `IPTV-SHARED-CAPTURE-DESIGN.md`, `IPTV-SHARED-CAPTURE-VALIDATION-20261005.json`, `IPTV-UPSTREAM-PR3788-REVIEW.md`, and `IPTV-RELEASE-NOTES-DRAFT.md`, all under `validation/`. `IPTV-PROGRESS.md` is chronological; older present-tense statements are superseded by this handoff and dated evidence. No AGENTS.md existed in applicable source/test/script/document ancestors at the latest check; obey any subsequently added instructions.

## User requirements and standing authorization

- Multiple M3U/M3U8 and Xtream Codes API sources, Stalker Portal, multiple XMLTV feeds from XML/gzip documents and URLs including shortened links; stable identities, safe refresh, mapping and profile isolation.
- Watch-first Nuvio Original/V2 UX using existing themes, fonts, focus/navigation and preferences. Full guide, Home favourites/sports/recordings, Search, appearances/locales/accessibility and AFR.
- Honest IPTV HUD, subtitles/CC/DVB subtitles/teletext, local pause/resume/timeshift, record-now and durable schedules to internal/USB/SMB storage.
- Governed multiview with a different source/playlist in each pane, explicit account groups, aggregate VOD/trailer/IPTV budgets, single audio/display owner and provider catch-up.
- Continue autonomously and keep release notes current, distinguishing implemented code, measured evidence, untested UI and future support.
- AM9 use and the existing comparator research are authorized. Preserve apps/accounts/preferences/recordings. No root/security changes, publication, messages to others, provider credential extraction/copying or unattended provider stress. Use a distinct prototype package/label and conservative unknown concurrency.
- TiviMate/NoBuffr are functional references only: independently validate behavior, do not extract/copy competitor APK code or UI. Research is sufficient to continue implementation; do not repeat it unnecessarily.
- Both earlier automations remain paused, including `nuvio-iptv-implementation-today`; do not resume them or create new ones. No subagents unless explicitly authorized by the user or applicable instructions.

## Current source, guide and foreground playback architecture

Core/data: `app/src/main/java/com/nuvio/tv/{core,data}/iptv`. UI/runtime: `ui/screens/iptv`. Inspect current APIs rather than copying old staged files.

Ingest: bounded strict M3U, source-scoped stable IDs, reconciliation/tombstones, last-good transactions and shrink review. Android Keystore/encrypted SQLite credentials/catalogue, schema 3. Overlays: favourite, hidden, custom name, manual guide and durable AUTO/HLS/MPEG_TS choice. Profile session fences revoke access before cleanup. Browse is paged (24 UI rows, max 200), revision-fenced and Unicode NFKC/ROOT searchable; never render whole-source snapshots. Current account alias is conservative `shared-default` pending explicit grouping UI.

Xtream: sequential authentication/categories/live metadata, active/expiry checks, strict UTF-8/JSON and bounded budgets. No media/logo/direct_source/advertised-host fetch on refresh. Encode credential components; advertised connection limits never raise capacity. TS preferred, explicitly advertised HLS accepted when TS absent. IDs preserve overlays across credential changes, but edits invalidate playback until refresh. VOD/series and automatic EPG/API integration remain unfinished. Metadata clients use Connection: close after a reproduced HTTP/1.0 stale pooled connection failure; no automatic retries.

XMLTV: bounded compressed/expanded input, no external entities, precise timestamps/feed-scoped mapping, transactional promotion, validators/last-good retention. Encrypted content-URI endpoints and OpenDocument persisted grants on Save, ContentResolver refresh; bad/revoked replacement preserves prior rows. Bounded guide redirects (six): same-origin HTTP or cross-origin HTTPS, no downgrade/userinfo/fragments, no cookies/auth/referrer, no validators across redirects or stored against a short URL's destination. Media redirects still rejected. User URL `https://tinyurl.com/epg-ss11` returned 404 on bounded HEAD/GET on 5 October; no contents validated. XMLTV is guide data, not playable channels.

Foreground: LIVE_CHANNEL bypasses VOD probes/cache/prefetch/thumbnails. `LivePlaybackRuntime` retains provider/decoder/memory until decoder and HTTP closure; stale owners cannot stop replacements and uncertain closure retains reservations. Current direct player rejects capture sharing. One foreground IPTV decoder/one unknown-capacity acquisition; 16 MiB acquisition + 96 MiB viewer estimates under 192 MiB budget, not measured global/device capacity. `LiveRequestFence` counts blocked connects, fences late opens and cancels/waits for request closure.

Media3 adapter uses dedicated OkHttp, conservative load control, 10s timeouts, no redirects/retries. Unknown-length/nonzero-offset entry rejection prevents TS tail probes; separate HLS segments may use ranges. Disable both load-retry delay AND rendition fallback: Media3 otherwise requested another rendition after a 503. Extensionless HLS works when explicitly selected; overlay changes apply on next activation without probing.

Prototype UI: preview/expanded video, source cycle, paged channels/favourites, focused programme details. Focus alone opens no media. Profile/navigation/background/error closes ownership; returning foreground does not auto-reopen. Expanded player still has prototype toolbar. Final immersive UX, grid guide, Home/Search and IME/focus checks remain.

HUD/track code from `0211656`: extracted existing `DebugStatsPanel`; synchronized live counters, player reads on main thread, 1Hz visible sampling. Reports source formats, buffer ahead, manifest offset or unavailable, body bytes/rate, active requests, tune-to-first-frame, rebuffer count/time and dropped renderer buffers. No URLs/credentials, inferred file size, speed-test, glass-to-glass or HDMI-rate claims. `IptvTrackDialog` has supported audio/text selection, Automatic/Off and stale-group checks; choices reset per player. Controls are not CC/DVB/teletext rendering certification.

## Capture implementation — internal, not enabled in player

`CaptureSegmentStore` (`8f1316e`): privately owned/exclusively locked directory; rejects unowned nonempty roots, symlinks and unexpected entries. Logical bytes/segment size/count (4096), index (1 MiB) bounded. Complete pending media and index are synced/atomically promoted; retire old files only after commit. Failed precommit reads/cancellation/oversize preserve committed rows; failed cleanup blocks new allocation. Pins protect an anchor and successors; open readers own pins. Protected capacity reports backpressure, not eviction; live pins prevent close. Latest bounds use exclusive end and exclude gaps/discontinuities. Extra pending/index/filesystem space and actual allocation/free-space enforcement remain necessary.

`SharedCaptureRuntime` (`4c3e0b0`): one producer/store, independently closing viewers/recorders. Infrastructure account/capture-memory/full spool reservation remains until final consumer, producer and store actually close. Consumer decoder/reader/pin closure must confirm. Stale tokens, foreign acquisitions, cancelled undelivered joins and incompatible plans are fenced. Uncertain closing fences new joins; `retryClosing`/`closeAll` preserve ownership for cleanup. Constructors open no media/decoder; factories must clean partial construction. Production integration must share ONE admission instance and real transport, not merely matching keys.

`SegmentCaptureTransport`: one source body/append/close at a time, no unbounded queue, prefetch or failed-request retry. Failure/backpressure halts ingestion. Cancel closes source to unblock reads, rejects late bodies and waits for actual termination. Failed/timed-out body cleanup retains a single owner; retry does not concurrently close it. Completion/failure is not proof of provider quota release.

`CaptureSnapshotReader`/`openSnapshotFrom`: atomically pins a finite contiguous sequence range, excludes future appends, stops at gap/discontinuity. EOF means fixed snapshot completion, not a temporarily empty live tail. Pin persists through EOF until explicit close. Store append holds its monitor through body copying; concurrent local playback latency still needs attention.

## Latest milestone: bounded HLS capture (`eaa43e5`)

New production files: core `HlsCapturePlaylist.kt`, `HlsCaptureSegmentSource.kt`; data `IptvCaptureHttp.kt`. Regression tests mirror these file locations in `src/test`; real Android response fixtures are in `src/androidTest/.../IptvCaptureHttpAndroidTest.kt`.

- Strict UTF-8 parser, default 256 KiB/1024 segments; bounded lines/URLs/target duration. Preserve sequence, discontinuity and EXTINF metadata; resolve same-origin HTTP(S) resources, no userinfo/fragments.
- Narrow single media-playlist/full-resource profile. Reject master/rendition selection, keys/encryption, maps/init, ranges, gaps, I-frame/partial/unknown EXT tags and cross-origin media BEFORE fetching their resources. This is a capture limitation; existing foreground HLS/range playback is unchanged. Independent-segments tags do not prove decode safety.
- Explicit capture begins at oldest advertised segment, then follows its sequence. Compare overlapping URI/duration/discontinuity on reload; halt on missing expected media, changed overlap or backward window instead of inventing continuity. Reload after target duration when changed, half-target when unchanged, with bounded stall waiting. Failed network requests are not retried; normal manifest polling is distinct.
- Dedicated single-request HTTP client: no unrelated player cancellation, redirect/retry/cookies/authenticator/cache. Identity encoding, status 200 only, declared/streamed body budgets, finite connect/read/call timeouts.
- Fence/cancel late opens and reclaim responses lost through prompt cancellation on dispatch back. Manifest bodies remain tracked on failed close, not just handed-out segments.
- **Critical close finding:** OkHttp Response.close/ResponseBody.close can swallow IOException. Close actual buffered source directly. It can mark itself closed before underlying close throws, so a later no-op close cannot prove release. Such uncertainty permanently fences that client instance and retains its reservation; never treat a retry no-op as provider release.
- Capture times accumulate manifest durations from zero (millisecond rounding). They are NOT measured PTS, wall-clock time, live latency or decoder-safe entry points. Captured content is not yet classified for codec/init/keyframe safety.

## Measured validation and practical limits

Latest exact evidence/hashes/APK hashes: `IPTV-HLS-CAPTURE-VALIDATION-20261005.json`.

- Full app Kotlin compile passed: 6m28s. All 176 IPTV JVM tests in 20 suites passed, zero failures/errors/skips; complete Gradle run 7m56s.
- Focused pure core: 130 tests, 0.687s. HTTP host fixtures: 10 tests, 0.653s, actual shipped OkHttp 5.3.2 Android AAR with API-0/logging stand-ins on JDK. Real loopback HTTP, not Android networking certification.
- Final Android harness built actual production sources in 33s; AM9 backend 190 tests passed in 35.827s. No INTERNET permission. Three HTTP tests use real Android/OkHttp with in-process interceptors. Suites overlap; do not sum as independent evidence.
- Host integration fetched one manifest/two synthetic byte resources through actual source/transport/store and read committed local bytes. It is NOT actual TS decode/playback or safe seek evidence.
- Installed prototype is still the older `0211656` HUD/guide build. No new prototype assembly/reinstall, provider traffic or HUD/subtitle/document UI certification this milestone.
- Prior evidence: HUD/guide 112 JVM/133 Android; shared capture 143 JVM/164 Android; isolated store seven JVM/seven Android. Preserve historical reports, use latest counts above for current reruns.
- Earlier controlled AM9 video: progressive TS, sliding HLS TS, ranges, extensionless choice, failure/replacement/favourites/paging/background checks. Synthetic 640x360/25fps H.264/silent AAC is not audibility, HDR/Atmos/UHD capacity or broad provider/codec certification.
- Not yet: decoder-safe bounds/local live tail, production sharing, enabled pause/timeshift/recording, physical disk margin/ENOSPC allocation, persistent leases/services/schedules, USB/SMB, abrupt process-kill/power-loss recovery or aggregate VOD/multiview budgets.

## Device and pending screen checks

Only authorized AM9 `192.168.10.60:5555`, AM9PRO Android 14/API34; always `adb -s`. Another device at .172 is out of scope. Package `com.nuvio.iptv.prototype`, label Nuvio IPTV Prototype; latest capture code is not installed. Temporary `com.nuvio.iptv.validation` and `.validation.test` packages removed. Pre-existing reverse tcp8765 preserved. No task fixture server/reverse18767 remains. No power/settings/account changes or credential access.

Earlier screen work was blocked by HDMI sleep shortly after wake, last sleep reason hdmi. During latest backend validation AM9 reported **Awake**, without this task waking it. That is an observation, not proof the screen will stay available. Inspect current state before UI work; don't repeatedly wake or change CEC/power settings. Earlier request to leave TV/AVR on was unanswered.

Outstanding screen checks: HUD preview/expanded/replacement/background; Original/V2 remote track focus, WebVTT select/off/render; document Save/Refresh/cold restart/revocation; source-form IME/password-to-Save focus and FlowRow wrapping. AM9 has no system OPEN_DOCUMENT/GET_CONTENT picker. Test-only synthetic picker/provider compiled/installed previously but never exercised; it does not solve shipping file import. Preserve last-good guide on revoked URI. A product alternative is still required.

`captures/iptv-hud/next-source.xml` from failed uiautomator is stale/invalid evidence; helper now removes old dumps, requires successful fresh output, 25s timeout. Black sleep captures are not playback failures. Initial awake screenshot was valid.

Prototype QA entry: `am start -n com.nuvio.iptv.prototype/com.nuvio.tv.IptvPrototypeActivity --ez live true --es appearance v2`; omit appearance for Original, force-stop prototype before changing extras only when authorized UI is available. Existing sources are synthetic: FixturePlaylist-Edited (33 channels), XtreamFixture (2; synthetic credentials fixture/fixture-pass), HlsFixture (4). Fixtures refer to stopped localhost18766/18767 servers: start controlled fixtures/reverses before playback, never silently use provider endpoints. Generated guide dates expire; regenerate dates for new checks. Extensionless HLS overlay is HLS; other channels Auto.

## Prior findings to carry forward

- PR #3788 review: `IPTV-UPSTREAM-PR3788-REVIEW.md`, pinned head `e10c639200d5821d1cbc71bdd438ccbd6b1ff7ff`, base dev `5c1d9b0e2669199114a12ade38027da132303eb3`. No import/cherry-pick/install/comment/review posted. Status may change; findings refer to that revision.
- Reproduced exact isolated policy: position50/buffer53, -30 pending20 then +10 selects60 instead of30 because pending anchor is used only for negative delta. Measured manifest offset20 can display LIVE due hardcoded3s baseline/locked filter. Recovery conflates RAM load target and playable retention (`shouldRejoinLiveEdge(90000,45000,1000)=true`). Implement scrub direction reversal/explicit return-live against actual retained bounds, not those shortcuts.
- TiviMate5.4.0 Beta2/code1000005402: one FHD50 fullscreen AFR test switched output50Hz, preview retained50; settings restored AFR off/Home59.94. No general AFR proof.
- NoBuffr1.0.0/210271: signed in, 13,740 channels, one app-reported2160p50/5.1 sports stream, 1GB timeshift, paused39s. Resume inconclusive (Up changed channel), recording/multiview capacity untested. Comparators were stopped after research; accounts/preferences retained. Do not repeat or assume these observations certify our implementation.

## Next implementation, in order

1. Validate actual controlled media and independently establish init/program/codec/keyframe/PTS entry points (initially a clearly bounded supported profile). Do not expose arbitrary byte chunks or manifest flags as safe seeks. Broaden capture protocols deliberately, including progressive TS and HLS init/ranges/renditions only with correct ownership/budgets.
2. Implement local live reader/Media3 timeline with distinct waiting/ended/expired/discontinuity states and actual decoder-safe retained ranges. Address store monitor/I/O contention. Pending scrub anchor must work in both directions; show reliable delay or unknown, explicit return-live.
3. Wire capture/player through one admission instance and actual shared transport; retain reservations to final confirmed consumer/request closure. Validate pause/resume/backpressure/expiry and capture-aware profile/navigation/background exits, physical spool/free-space margins before exposing controls.
4. Durable record-now, leases/schedules, foreground service and process-death reconciliation; internal/USB/SMB allocation/delivery, safe kill/exit/recovery paths. Store+adapter alone is not recording.
5. Stalker fixtures/user-configured source; automatic Xtream XMLTV/API EPG; account groups, source/feed management, guide priority/mapping/facets/paging and rejected-candidate review. Govern different-source multiview, provider catch-up and aggregate VOD/trailer/IPTV/audio/display/AFR ownership.
6. Full guide/Home/Search, watch-first immersive UX, preferences/locales/accessibility and subtitle/CC/DVB/teletext/hardware coverage. Complete outstanding screen checks when available; do useful independent work around screen/provider blockers. Maintain comprehensive release notes/evidence.

## Toolchain and commands that actually passed

PowerShell/Brisbane; JDK `E:/Android Studio/jbr`; Gradle cache `D:/DevData/Gradle`; Android SDK `E:/DevData/Android/Sdk`, AAPT36.1.0; Python `C:/Users/PWR/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`. ffmpeg/ffprobe on PATH. Explicit UTF-8 text I/O. gh unavailable; connector tools existed earlier. CODEX_HOME env unset, actual directory `C:/Users/PWR/.codex`.

The project defaults to 6 GiB Gradle heap. Unrelated toolchains were resident. Latest first3GiB attempt failed native allocation; 2GiB was too small (GC thrashing). These command-line-only limits succeeded; do not modify system page file/project settings or stop unrelated daemons:

```powershell
Set-Location 'E:/Codex/NuvioTV/work/NuvioTV-IPTV'
$env:JAVA_HOME = 'E:/Android Studio/jbr'
$env:GRADLE_USER_HOME = 'D:/DevData/Gradle'
$env:ANDROID_HOME = 'E:/DevData/Android/Sdk'
./gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
# After compilation, tests against unchanged compiled outputs:
./gradlew.bat :app:testFullDebugUnitTest --tests 'com.nuvio.tv.core.iptv.*' --tests 'com.nuvio.tv.data.iptv.*' --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
./gradlew.bat -p tools/iptv-device-tests assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

The cached production core runner `scripts/check_iptv_core.py` now includes coroutines1.10.2 on its test classpath (already the app runtime, not a dependency upgrade). See harness README for install/instrument/uninstall; assert JUnit `OK (...)`, not just adb exit0. Both APK permission dumps verified no INTERNET (test has REORDER_TASKS only). Full compile/JVM run each takes several minutes; redirect baseline warnings to task logs and communicate progress.

Current task sandbox covered original Fork, not sibling. Concrete sibling writes/builds/commits used authorized require_escalated shell tools and automatic review approved them; no rejected permission remains. Do not move implementation to original to evade sandbox. Staging in original ignored captures plus branch-guarded copies is available.

## Scratch, evidence and separate upgrade work

- Latest original scratch `captures/iptv-hls-capture`: new files, private host runner with API-0/logging stand-ins, fixture output, own crash/replay logs and documentation scripts. **Never rerun sync-new.py, update-docs.py, finalize.py or prepare scripts over authoritative newer code/docs.** These are one-shot historical helpers, not continuation entry points.
- Earlier `captures/iptv-shared`, `iptv-capture`, `iptv-hud`, `iptv-guides-local`, `pr3788`, `iptv-hls/xtream/live/setup` contain fixtures/repro material. Old run-tests.ps1 can compile staged bytes; point new checks to authoritative source first. `iptv-hud/fixture.py --directory captures/iptv-hud --subtitles` supplies loopback18767 with generated TS/WebVTT; device.py requires fresh dumps. Committed HLS fixture: `tools/iptv-device-tests/hls_fixture.py`; private WebVTT extension not promoted there.
- Earlier comprehensive research: original Fork `validation/iptv-research/NEXT-SESSION-HANDOFF.md` and related design/evidence. Requirements/context only, not source to resync.
- Component work was explicitly handed off separately. Handoff: original Fork `captures/component-upgrades/NuvioTV-component-upgrade-handoff-2026-10-05.txt`; inventory: `captures/iptv-deps/outputs/01a10ae2-ecd3-7d41-9d5b-3543314143be/NuvioTV-component-versions-2026-10-05.xlsx`. No library/native/toolchain versions changed in this IPTV milestone. Coordinate eventual integration only when requested; do not start a blanket upgrade in this session.
- No task fixture servers, background build sessions, active subagents or goals remain. Both automations stay paused. Reinspect device/worktree state before new tests. Keep draft release notes current and distinguish code, measured evidence and unfinished user-facing features.
