# Nuvio IPTV - authoritative fresh-session handoff, 6 October 2026

> Cloud transfer: use `ysosrs123/NuvioTV-Fork` branch `iptv/wip` and read
> [IPTV-CLOUD-HANDOFF.md](../IPTV-CLOUD-HANDOFF.md) first. Current player WIP is
> included in that branch. Windows paths and local-only restrictions below apply
> to the PC checkout; they do not require cloud access to E:. The user authorized
> this WIP GitHub transfer on 6 October. Existing device/provider/automation rules remain.

## Read first: authority, workspace and continuation

The user explicitly requested a NEW/fresh chat to continue implementation, a
complete handover of context/findings/solutions/remaining work, and updated draft
release notes. This authorizes creating that chat and continuing concrete work.
It supersedes the overnight no-new-chat restriction only. No subagents, blanket
component upgrades, publication or messages to unrelated chats/people are authorized.
The dependency/component upgrade work is a separate task.

Authoritative implementation is ONLY `E:/Codex/NuvioTV/work/NuvioTV-IPTV`, on
existing branch `codex/iptv`. Latest continuation: AM9 capture-path fix/evidence below, based on transfer `81540e1`.
Prior overnight implementation: `6ad3799f93eef8e61216998f82b4791af22b3d47`.
Pre-transfer documentation HEAD: `6f4a01d` (confirmed clean). Read current log/status;
the transfer itself adds a documentation checkpoint without changing tested source.
Published base remains nt4.1/build1458 (`574a2d41257ccc667828f54ba0c6ef40d9784984`).

The app's ONLY registered project is NuvioTV-Fork (project ID
`7af1c794-36cb-4416-b1c1-bfcaa2d07cdf`). Its saved C: alias and operational
`E:/Codex/NuvioTV/work/NuvioTV-Fork` are the WRONG, dirty checkout for IPTV. A new
local project chat may therefore start with that default cwd. Treat that cwd as
non-authoritative. Do not reset, switch, repair, stash, commit, edit or stage IPTV
there. Do not change that checkout to repair the project's saved path.

Pass the exact IPTV sibling as workdir to EVERY shell operation, or use explicit
`git -C E:/Codex/NuvioTV/work/NuvioTV-IPTV`. If sibling writes/builds/commits are
outside the new chat sandbox, use require_escalated with concrete justification
for the user-authorized sibling operation; these operations were reviewed and
approved here. Never relocate implementation or staging into Fork to evade the
sandbox. No outstanding automatic-approval rejection exists. Check for new
AGENTS.md in applicable ancestors; none was found at this transfer check.

All THREE automations are PAUSED: `iptv-research-follow-up`,
`nuvio-iptv-implementation-today`, `nuvio-iptv-overnight-continuation`.
The temporary overnight job was explicitly paused at 07:05 Brisbane, 6 October,
before its 08:00 cutoff. Leave all three paused; morning manual continuation is
not authorization to restart/create recurring automations.

## Player-binding cloud continuation — 6 October 2026

Branch iptv/player-binding, derived from iptv/wip 8b84c11. The last device-validated
checkpoint remains b68985a. Details, hashes and limits:
[IPTV-CAPTURE-PLAYER-CLOUD-20261006.json](IPTV-CAPTURE-PLAYER-CLOUD-20261006.json);
code review findings: [IPTV-CODE-REVIEW-20261006.md](IPTV-CODE-REVIEW-20261006.md).

Probable cause of both AM9 player timeouts, from code inspection and a JVM reproduction
(not yet confirmed on device): CaptureSampleBatchQueue (maxBatches 2) and
CaptureSampleLoadCursor (maxOpenInputs 2) stage at most two rows. The three-segment
fixture staged segments 0-1 and stopped at CAPACITY. Only tests called
retireConsumedPrefix, so no played row was ever returned; segment 2 was never staged,
the reader never reached ENDED and the video stream returned NOTHING_READ after about
4s. ExoPlayer stayed BUFFERING until the 30s wait expired.

- CaptureEpochMediaSource(retiresPlayedBatches = true) makes period discardBuffer
  retire rows wholly before the playback position through the existing checked
  retireConsumedPrefix path. Default remains off; CaptureVideoPlayer requires it.
- Media3 MaskingMediaSource replaces an explicit start of 0 with the window default
  position (the live edge). CaptureVideoPlayer startPositionMs is now nullable: null
  starts at the live edge; explicit positions are honoured, with 0 sent as 1ms, which
  frame-floors to the first retained frame.
- CaptureVideoPlayer.describe() and the fixture's timeout path now report player,
  window, decoder-counter, reader, transport and render state instead of a bare timeout.
  The fixture also asserts the first rendered frame comes from the requested start.
- Epoch boundaries and STOPPED readers no longer abort playback early:
  readDiscontinuity and SampleStream.maybeThrowError raise them only once the stream
  has played all staged rows (review finding 3a). Ownership loss still fails at once.

Cloud evidence: core 211/211 pass with the existing runner and pinned Kotlin 2.3.0.
A cloud JVM harness ran the 13 data-layer capture suites (66 cases: 64 existing plus
two new regressions) against the shipped Media3 AAR classes, Media3 1.8.0 lib-decoder
built from source and a default-value android stub generated from android-all. The new
cases fail without their changes and pass with them. The AM9 fixture compiles against the
same classes with a stub InstrumentationRegistry. These are not Gradle/AGP builds: the
Android SDK host is blocked in the cloud, so no full app compile, full 317-case JVM
run, harness APK or device execution has been done for this change.

Next local steps: full app compile, full IPTV JVM run, harness build, then the two
CaptureVideoPlayerAndroidTest cases on AM9 using the existing README commands and
device rules. Controls stay disabled. Rendering, preroll discard, seek acknowledgement,
blocked-release retention and measured memory remain unclaimed until those pass.

## Player-binding WIP — cloud transfer status (superseded by the section above)

Last validated committed checkpoint is b68985a. Subsequent player-binding source /
tests are included on iptv/wip for the authorized cloud review.
Read [the current WIP transfer note](IPTV-CAPTURE-PLAYER-WIP-20261006.md) and its
JSON report before editing or building. Core211 passes; the current harness builds,
but both actual AM9 player fixtures time out. No new successful player/rendering
claim or full app/JVM run is established. Both test packages are removed; no task
build/test is running. Cloud work can continue from the published WIP branch.
The committed AM9 capture-path checkpoint below remains valid historical evidence.

## AM9 capture-path validation continuation - 6 October 2026

Manual continuation from clean transfer 81540e1 in the IPTV sibling. This section
supersedes earlier compile-only claims for the cases executed here; historical
reports retain their original outcomes. Automations remain paused.

The first headless AM9 run executed 21 cases in 53.356s with 12 failures. The
shipped HLS extractor eagerly creates an empty ID3 track even when the inspected
PMT contains only AVC/AAC. Strict staging rejected that third track, causing
upstream reader failures and downstream source/period waits. The scoped local
factory now declines the synthetic ID3 reader; actual ID3 media remains outside
the inspected profile. Strict two-track/budget/hash checks and libraries are unchanged.

After the fix, 22 focused cases passed in 7.454s. A final expanded run passed
27 cases in 14.665s, including a new exact-two-track regression, actual Android
Source/Handler/period/reader/queue/stager/storage fixtures and all TS codec cases.
These runs overlap; do not sum them or add them to the existing JVM count.

New normalized codec coverage stages all three original segments transactionally
and passes their samples to fresh headless codecs. All 150 video and 283 audio
samples decode with exact input/output PTS agreement. The first audio PTS remains
-21333us; successor video starts remain 2000000/4000000us and audio starts
2005333/4010667us. Codecs: c2.amlogic.avc.decoder and c2.android.aac.decoder.
This is buffer decoding, not audible/visible rendering, renderer preroll discard,
continuous ExoPlayer playback, exact seek acknowledgement or aggregate memory proof.

AM9 192.168.10.60:5555 stayed Asleep at before/after checks. No activity, wake,
power/CEC/settings/account/recording/provider operation occurred. Only temporary
validation packages were installed and both were successfully removed. Reverse
list was empty; no reverse mapping was changed. The prototype was not replaced.
The target APK has no permissions; instrumentation has only REORDER_TASKS.

Final whole-app Kotlin compilation passed in 498s (8m18s). All 317 IPTV JVM
cases across 38 suites passed with zero failures/errors/skips (10.676s test /
286s build). Final headless harness build passed in 24s. These host suites overlap
prior core/JVM evidence. Source/components/APKs/logs and fresh XML hashes were
verified in the device validation report. No task build remains running.

Source/player/renderer binding through one admission and actual shared transport,
renderer discard/seek acknowledgement, measured aggregate memory and production
storage margins remain the next gates. Durable recording and remaining provider /
UX work stay outstanding; controls remain disabled. Exact source/APK/log/build
results are recorded in IPTV-EPOCH-DEVICE-VALIDATION-20261006.json.

## First actions in the new chat

1. Verify exact sibling, branch, HEAD/status and any running build before starting
   another. Do not rebuild unchanged source merely to reproduce completed evidence.
2. Read this file, `IPTV-EPOCH-SOURCE-DESIGN.md` and its validation JSON, the
   epoch-period and incremental-reader designs/reports, then `IPTV-TS-ENTRY-DESIGN.md`
   and the current draft release notes. All are in this sibling's validation/.
3. Read IPTV-EPOCH-DEVICE-VALIDATION-20261006.json for the new executed AM9
   source/period/stager/storage and normalized codec evidence. Do not repeat the
   completed checks without a changed source or unresolved issue. Next bind actual
   admitted player/renderers and validate renderer discard/seek acknowledgement.
   Recheck authorized device state before any device work; preserve all restrictions.
4. Keep controls disabled until ownership, decoder, measured memory and physical
   storage gates pass. Save exact evidence and local validated codex/iptv checkpoints.

This document supersedes ALL older present-tense/Next sections in
`IPTV-NEXT-SESSION-HANDOFF-20261005.md`, `IPTV-PROGRESS.md` and historical release
milestones. Preserve those files and reports for provenance; do not repeat finished
research or undo newer code because an old milestone says it remains unimplemented.

## Latest measured evidence and its limits

Current device continuation: `IPTV-EPOCH-DEVICE-VALIDATION-20261006.json`.
The following epoch-source evidence is historical: `IPTV-EPOCH-SOURCE-VALIDATION-20261006.json`.
Full app compile PASS 451s; full IPTV JVM PASS **317 tests / 38 suites**, zero
errors/failures/skips, 8.747s test / 381s build; headless harness builds PASS 53s.
The unchanged **207 core cases overlap** the full JVM run; their last standalone
run was 2.179s at the incremental-reader checkpoint. Do not sum overlapping counts.
Source/component/APK/manifest/log/all-suite XML hashes are saved in reports.
Source/component/APK hashes were rechecked for this handoff and matched.
No task build/server/subagent/goal was left running; recheck fresh state.

The ten latest host cases are seven callback-gate cases using a synthetic async
poster and three ACTUAL MediaSource pre-Looper owner/runtime cases using synthetic
compressed samples. They do NOT execute BaseMediaSource.prepareSource/Handler
playback callbacks, an ExoPlayer, renderer, codec, audio/display or a durable recorder.
Two actual Source/Looper/stager/period Android fixtures COMPILE ONLY, as do earlier
new staging/period/reader/queue/storage fixtures. No new Android run is claimed.

Earlier TS entry AM9 validation passed 214 overlapping backend tests. The scoped
bridge decoded 150 video and 283 audio samples across the original three segments
(50 video each; 95/94/94 audio). FFprobe and strict FFmpeg agreed. That was raw
positive PTS, not current normalized negative audio/renderer/source certification.
Final annotation-only harness rebuild was not reinstalled. Keep evidence versions
and superseded initial/final builds distinct; never overwrite their named logs.

## Implemented capture chain and important solutions

- Store: CaptureSegmentStore owns/exclusively locks a bounded directory, complete
  media/index sync plus atomic promotion, pinned anchor/successors, last-good rows,
  backpressure and retryable failed cleanup. Append copying/sync uses a separate
  writer lock so local snapshot/open/pin access works during slow input. Index
  publication/retirement still has bounded local IO under the state monitor.
- Ownership: SharedCaptureRuntime owns one actual transport/store and independent
  viewer/recorder consumers through one admission instance. Failed joins preserve
  existing consumers; uncertain closure retains infrastructure, account, memory,
  spool and pins. Matching keys alone do not prove real shared acquisition.
- Transport: SegmentCaptureTransport has one body/append/close at a time; committed
  sequence hints publish only AFTER atomic append returns. Bounded HLS source/HTTP
  ownership fences late/cancelled/lost responses, missing/changed overlaps, failed
  manifest-body closure and unsupported playlist resources. No failed-request retry.
  Critical finding: OkHttp close can swallow underlying IOException; a later no-op
  close is not release proof. Close the actual source, retain uncertainty/charge.
- Inspection: TsCaptureInspector supports a narrow single-program Baseline AVC /
  AAC-LC complete MPEG-TS profile with intact PAT/PMT/continuity, in-band stable
  init, initial IDR, bounded samples/PES/packets, coherent video/audio PTS. It is
  header inspection, not arbitrary coded-payload safety or a general codec decoder.
  CaptureTsInspectionIndex binds immutable evidence to exact committed row/owner;
  open media verifies length/hash through EOF and retains its exact input/pin.
- EOF bridge: platform and shipped HLS extractors each omitted final video sample.
  Shipped PesReader scratch reuse prevents final AVC flush. LocalTsSegmentExtractor
  is a scoped unchanged-AAR bridge; only hash-matched complete segments receive
  the synthesized empty-PUSI finalization. Output is tentative until success, so
  staging is transactional. No invented media frame or binary/dependency upgrade.
- Timeline/seek: CaptureSampleTimeline keeps actual PTS origin across eviction/wrap
  and explicit codec/capture/timestamp epochs. Actual video/video-audio phase, not
  EXTINF, determines positions. CaptureSeekController uses pending anchor in BOTH
  directions, rejects stale/expired commits and exposes explicit captured-tail.
  This is not wall-clock LIVE or actual player/render acknowledgement.
- Staging: LocalCaptureSampleStager checks verified EOF/formats/counts/keyframe/PTS
  and cancellation before publication. One video epoch preserves audio phase,
  including first audio -21333us in fixtures. Limits bound encoded bytes/init,
  samples and dimensions; transient/object/native/decoder budgets are not measured.
- Physical fences: fresh free-space/allocation-unit/volume observations plus
  explicit margins and worst-case pending/index overhead gate runtime admission,
  per-write and index publication. Host cluster4096 differs from JDK sector512;
  bounded Win32 observation fixed that. Observations are NOT OS preallocation,
  production AM9/USB/SMB margins, ENOSPC or physical power-loss certification.
- Finite reader/period: PinnedCaptureSegmentPeriod and PinnedCaptureReaderConsumer
  deliver one exact verified staged segment, frame-floor seeks and full batch IDR /
  audio preroll. Async stale/cancelled completion is fenced; failed/timed-out close
  shares one closer and retains handles/pins/reservations until confirmation.
- Incremental queue: CaptureSampleLoadCursor loads exact successors, retains ticket
  and waiting/terminal anchor pins, distinguishes WAITING/CAPACITY/ENDED/STOPPED /
  EXPIRED/DISCONTINUITY/FAILED/CANCELLED and never jumps/retries/crosses an epoch.
  CaptureSampleBatchQueue reserves worst-case next encoded batch/slot BEFORE open,
  publishes transactional stages, retains failed candidates and fences reentry.
- Async reader: IncrementalCaptureReaderConsumer has one IO worker/metadata
  observer, conflated capture/explicit hints and cached immutable batches/Media3
  metadata. Exact object/revision fences reject stale/foreign/retiring views.
  Declared encoded-resident floor applies BEFORE transport/consumer start.
- Growing period: CaptureEpochPeriod has one exclusive reader borrow. Live tail
  returns NOTHING, only exact COMPLETE becomes EOS. Growth retains cursor/PTS;
  seeks feed selected batch initial IDR/audio phase. Explicit consumed-prefix
  transfer checks selected tracks finished those rows, keeps one row and closes /
  refills off-thread; UID/epoch origin stays stable. continueLoading cannot create
  local tail polling. EOF and period closure do not acknowledge decoder shutdown.
- Source: CaptureEpochMediaSource is ACTUAL BaseMediaSource + OwnedCaptureConsumer,
  owning reader/metadata observer and one queued/in-flight playback callback via
  CapturePlaybackRefresh. Cached-only playback-thread access, exact current UID /
  target, unresolved initially empty running capture, explicit epoch errors.
  Preparation refreshes latest cache to cover queued-before-prepare races; period
  close clears own format/init references. Owned close requires observer/callback
  quiescence, period AND BaseMediaSource caller release and reader/input closure.
  Actual renderers must be stopped/confirmed separately; untested Native gates stay
  disabled. The direct foreground player still rejects capture sharing; UI not wired.

Current code: app/src/main/java/com/nuvio/tv/{core,data}/iptv; foreground/UI:
app/src/main/java/com/nuvio/tv/ui/screens/iptv. Inspect actual classes/APIs.

## Checkpoint/evidence map (under validation/)

| Commit | Added | Design + validation report stem |
| --- | --- | --- |
| 43b1dff | TS inspection, local reader, EOF bridge | IPTV-TS-ENTRY (20261005) |
| 25395a2 | pinned inspection, sample epochs, seek policy | IPTV-RETAINED-MEDIA (20261005) |
| 4606418 | transactional staging, Media3 metadata | IPTV-SAMPLE-STAGING (20261006) |
| ae0c08b | physical storage fences | IPTV-STORAGE-FENCE (20261006) |
| 6842df1 | finite pinned period | IPTV-PINNED-PERIOD (20261006) |
| a960696 | async exact-seek reader | IPTV-PINNED-READER (20261006) |
| 0b82357 | incremental inspection/TS queue | IPTV-SAMPLE-LOAD (20261006) |
| 34a4937 | governed incremental reader | IPTV-INCREMENTAL-READER (20261006) |
| ed528c6 | growing epoch period/borrow | IPTV-EPOCH-PERIOD (20261006) |
| 6ad3799 | source/callback binding | IPTV-EPOCH-SOURCE (20261006) |
| 6f4a01d | overnight closure/paused-job summary | morning summary / existing handoff |

Earlier architecture: eaa43e5 HLS source/HTTP fences; 4c3e0b0 shared capture;
8f1316e store; 0211656 HUD/document guides/secure guide redirects/native tracks.
All detailed findings, exact reports and the earlier ingest/guide/playback sequence
remain in the older handoff and draft notes. Keep report filenames/date suffixes
as stored; use rg --files validation to locate a report, not historical scripts.

## Existing ingest/guide/prototype architecture to preserve

Bounded M3U ingest, source-scoped stable IDs/reconciliation/tombstones, last-good
transactions and shrink review; encrypted Keystore/SQLite credentials/catalogue
(schema3), profile session fences and overlays for favourite/hidden/name/manual
mapping and AUTO/HLS/MPEG_TS. Paged browse24/max200, revision-fenced Unicode search.
Current conservative account alias is shared-default pending grouping UI.
Xtream sequential auth/categories/live refresh checks active/expiry and encoded
credential components; no media/logo/direct_source/advertised-host speculative
fetch. TS preferred, advertised HLS fallback. Metadata uses Connection: close
for reproduced HTTP/1.0 stale-pool failure, without automatic retry.

Multiple XMLTV URL/gzip/document feeds, precise timestamps/feed-scoped mapping,
transactional promotion and last-good/cache validators; encrypted content URIs
and persisted grants on Save. Guide redirects are bounded6, same-origin HTTP or
cross-origin HTTPS, no downgrade/userinfo/fragments/cookies/auth/referrer, with
validator isolation. Media redirects remain rejected. tinyurl.com/epg-ss11 was
404 in bounded public HEAD/GET on 5 October; no guide content certified.

LIVE_CHANNEL bypasses VOD probes/cache/prefetch/thumbnails. LivePlaybackRuntime /
LiveRequestFence retain provider/decoder/memory through confirmed closure, fence
late connects/stale replacements/background/profile exits, and never auto-reopen
on foreground return. Unknown capacity: one acquisition/one foreground decoder;
16MiB acquisition +96MiB viewer under192MiB are ESTIMATES, not measured total limits.
Player disables both failed-load retry delay AND alternate HLS rendition fallback
(after a reproduced503 fallback). Explicit HLS handles extensionless URLs; format
choice applies next activation with no probe. Existing foreground HLS/range support
is broader than the capture subset; do not accidentally narrow it.

Prototype has Original/V2 source forms, preview/expanded video, source cycle,
paged channels/favourites and focused programme info. Focus opens no media. HUD
uses existing presentation, main-thread player reads, 1Hz visible sampling,
actual format/buffer/body-rate/request/tune/rebuffer/drop metrics without URLs /
credentials, inferred file-size, speed-test, glass-delay or HDMI-rate claims.
Track dialog supports reported audio/text, Automatic/Off/stale-group checks;
choices reset per player. No general CC/DVB/teletext rendering certification.

## Next implementation - current priority and all remaining scope

1. Source/Looper, period, reader/queue/stager/storage and normalized codec cases
   now pass on AM9 (see new continuation/report). Next implement actual governed
   ExoPlayer/renderer consumers with one admission and actual shared transport,
   output/preroll discard and exact pending seek/render acknowledgement. Preserve
   reservations through uncertain player release; source close alone is not proof.
2. Measure aggregate transient/parser/object/staging/Java/native/graphics/decoder
   budgets above encoded floor and real physical margins. Validate pause/resume,
   backpressure/expiry, navigation/profile/background exits and confirmed teardown.
   Do not force enabled controls as a workaround for unvalidated ownership.
3. Durable record-now, persisted leases/jobs/schedules, foreground service/process
   death reconciliation, safe cancel/exit/recovery; internal/USB/SMB allocation and
   delivery. Mock recorder survival and atomic spool sync are NOT durable recording
   or physical power-loss evidence. Broaden progressive TS/HLS init/ranges/renditions
   only with supported entry points, ownership and budgets.
4. Stalker Portal fixtures/user-configured adapter; automatic Xtream XMLTV/API EPG;
   explicit source/account groups, feed/source management, priority/mapping/facets /
   paging and rejected-candidate review. Preserve overlays/profile isolation.
5. Different-source multiview, catch-up and aggregate VOD/trailer/IPTV ownership,
   single audio/display owner, provider limits and AFR/display handoff.
6. Full guide grid, Home favourites/sports/recordings, Search, immersive watch-first
   Nuvio Original/V2 UX, source-form IME/Save focus, HUD/replacement/background,
   remote audio/text select/off/render, preferences/locales/accessibility and
   representative subtitle/CC/DVB/teletext/hardware validation.

Do useful independent work around device/provider/UI blockers; do not repeat
finished competitor/upstream research or silently fetch real providers.

## AM9, fixtures and device boundaries

ONLY serial `192.168.10.60:5555` (AM9PRO Android14/API34), always explicit adb -s.
Another device .172 is out of scope. Latest cleanup query was about 08:04 Brisbane
on 6 October: targeted dumpsys power mWakefulness=Asleep. This is historical,
not a current screen observation. The headless tests did not wake the device. Reinspect state, never wake TV/AVR or change
power/CEC/accounts/settings/recordings. No credential extraction/copying or provider
stress. Preserve comparator accounts/preferences and distinct prototype.

Installed `com.nuvio.iptv.prototype` (Nuvio IPTV Prototype) remains older0211656
HUD/guide build; latest capture code not installed in that prototype. The headless
validation packages were installed for this morning continuation and both removed
afterward. Only remove task-owned com.nuvio.iptv.validation
and com.nuvio.iptv.validation.test after tests; no broad cleanup/force-stop/process
termination. Latest morning cleanup reverse list was EMPTY; historical8765 is not assured.
No task fixture servers/reverse18767 remain. Reinspect before touching any mapping.

Executed fixture classes in app/src/androidTest/java/com/nuvio/tv/data/iptv:
CaptureEpochMediaSourceAndroidTest (2), CaptureEpochPeriodAndroidTest (2),
IncrementalCaptureReaderConsumerAndroidTest (2), CaptureSampleBatchQueueAndroidTest
(3), PinnedCaptureReaderConsumerAndroidTest (2), PinnedCaptureSegmentPeriodAndroidTest
(2), LocalCaptureSampleStagerAndroidTest (7), AndroidCaptureSpaceProbeTest (2).
Those 21 original cases now execute successfully in the device continuation;
LocalCaptureSampleStagerAndroidTest now has 7 cases with the ID3 regression.
TsCaptureDecodeAndroidTest now has 5 cases including normalized staged decoding.
The final focused suite is 27 tests, with overlapping earlier runs preserved.
The older raw-PTS bridge/codec tests also reran successfully in that final suite.

The tools/iptv-device-tests harness Sync compiles ACTUAL app/src production code,
not scratch copies, with unchanged shipped AARs. Use its README for exact install /
instrument/uninstall commands; prefer focused headless filters before broader
reruns. Check final packaged permissions with aapt; current target merged manifest
has no permissions/activities, current source report records its hash. README's
older ACCESS_NETWORK_STATE observation is historical; inspect the actual APK.
Do not launch the optional synthetic picker activity as part of backend tests.

AM9 has no system OPEN_DOCUMENT/GET_CONTENT picker. Synthetic picker/provider is
compiled but its UI/grant flow remains unexercised; product import alternative is
still required. Preserve last-good guide on revoked URI. UI fixtures point at
stopped localhost18766/18767, with synthetic playlists; regenerate expired guide
dates and use controlled fixture servers/reverses rather than provider endpoints.
Black HDMI-sleep screenshots/stale uiautomator dumps are not playback evidence.

## Toolchain, proven commands and evidence handling

PowerShell +Australia/Brisbane. JBR E:/Android Studio/jbr; SDK E:/DevData/Android/Sdk;
Gradle cache D:/DevData/Gradle; Gradle8.13. Python executable:
C:/Users/PWR/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe.
ffmpeg/ffprobe on PATH. Explicit UTF-8 text reads/writes (logs utf-8-sig).

From exact sibling workdir, set JAVA_HOME, GRADLE_USER_HOME, ANDROID_HOME to the
paths above. Proven command-line-only flags (no project/system settings change):

```powershell
./gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx3072m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
./gradlew.bat :app:testFullDebugUnitTest --tests 'com.nuvio.tv.core.iptv.*' --tests 'com.nuvio.tv.data.iptv.*' --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
./gradlew.bat -p tools/iptv-device-tests assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=1 '-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=512m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=128m -XX:ActiveProcessorCount=2 -Dfile.encoding=UTF-8' '-Pkotlin.compiler.execution.strategy=in-process'
```

Run serially after inspecting any running build. Redirect to newly named phase
logs under validation/iptv-core; assert command completion, BUILD SUCCESSFUL and
fresh JUnit XML counts. Poll own sessions with write_stdin, not unrelated processes.
Do not gradle --stop globally, modify page file/project heap or kill other builds.
The core runner scripts/check_iptv_core.py uses current source/cached Kotlin;
run only for relevant changes. Successful app/JVM runs take several minutes.
Headless APKs: tools/iptv-device-tests/build/outputs/apk/debug/
NuvioIptvDeviceTests-debug.apk and apk/androidTest/debug/
NuvioIptvDeviceTests-debug-androidTest.apk. Shared outputs may be rebuilt: report
hashes describe their measured checkpoint; superseded hashes/logs stay preserved.

Original TS fixtures: app/src/test/resources/iptv-ts. tools/iptv-device-tests/
validate_capture_media.py independently probes/strictly decodes them. Do not
regenerate over committed inputs. Use empty task directories for new fixtures.

## Historical research and scratch - read-only context

Older full context/architecture and precise upstream/comparator reproductions:
IPTV-NEXT-SESSION-HANDOFF-20261005.md, IPTV-UPSTREAM-PR3788-REVIEW.md and earlier
reports. PR3788 pinned e10c639200d5821d1cbc71bdd438ccbd6b1ff7ff/dev base5c1d9b0;
not imported/published. Pending-anchor reversal, hardcoded live baseline and
RAM-vs-playable-retention findings are already addressed in new core policy but
still need actual player/UI acknowledgement. TiviMate FHD50 AFR and NoBuffr one
app-reported2160p50/5.1/paused39s observations do not certify our capacities;
resume/recording/multiview were inconclusive or untested. No code/UI copying.

Original Fork captures/ and earlier one-shot staging/finalization scripts are
HISTORICAL. Do not rerun sync-new.py, update-docs.py, finalize.py, prepare scripts,
capture-staging/finalize_sample_staging.py, storage-fence/finalize_storage.py or
older TS/sample-load finalizers over current code/docs. No implementation edits
or temporary staging in Fork. Existing helper scripts may point at obsolete bytes.
Separate component-upgrade handoff/inventory locations remain in the older handoff;
do not upgrade dependencies, create more chats/subagents or message that task.

Maintain comprehensive draft release notes, progress, fresh handoff, exact reports
and local validated commits. Distinguish code, host tests, actual device execution,
untested UI, estimated vs measured budgets and remaining support at every milestone.
