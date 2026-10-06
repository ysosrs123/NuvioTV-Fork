# Nuvio IPTV — draft release notes

Unreleased development branch: iptv/player-binding (from iptv/wip). Updated 6 October 2026.
Base: 1.1.0-beta-nt4.1 / build1458. Latest continuation: AM9 capture-path fix/evidence below (base81540e1);
prior overnight implementation: 6ad3799;
07:05 overnight closure checkpoint: 6f4a01d. Capture code is internal and gated;
the installed distinct prototype remains the older HUD/guide build. Nothing published.

## Review fixes — cloud continuation, 6 October 2026

Branch iptv/player-binding. Full findings and per-item status:
[IPTV-CODE-REVIEW-20261006.md](IPTV-CODE-REVIEW-20261006.md). Fixed in source:

- Capture/period: final frame kept for declared-length video PES (packet-fed
  extractor clears video PES lengths after hashing); atomic current-snapshot borrow
  for createPeriod; live-tail skipData stops at the last keyframe; timeline keeps up
  to 4096 windows; boundary/stop errors raised only at the stream tail.
- Ownership: admission close tickets completed at non-owner release; transport close
  joins the worker and re-confirms the HLS source; failed body close retried; primary
  BACKPRESSURE/STORAGE_BLOCKED kept; later joiners start an unstarted transport.
- Ingest: overlay-less catalogue tombstones dropped (over-cap returns INVALID); XMLTV
  byte runs bounded before token construction; bounded guide quarantine and duplicate
  channel merge; folded search with schema v4 re-index; guide URLs withheld from logs;
  validators not sent after redirects; six redirects followed.
- UI: Sources/Live back stack; profile credential removal and Live session open off
  the main thread; background refresh keeps focus/programmes; extension renderers for
  live audio; guide document grants released when unused.

Open: 12 (Keystore cost per catalogue row needs device timing), 20 (whether full
builds should show IPTV). No change by design: 7, 8, 18, 21 (reasons in the review).

Verification in the cloud: core 222/222; data-layer JVM 117/117 across every
com.nuvio.tv.data.iptv suite (real stager/extractor, MockWebServer HTTP clients).
Each fix with a test was checked to fail without its change. All 18 IPTV androidTest
classes and IptvLivePlayback compile against the shipped Media3 classes with stubs.
Not compiled here: Compose/Hilt UI files (navigation, ViewModels, settings) and
ProfileManager; not run: full app compile, Gradle unit tests, device instrumentation.
Run those locally before trusting UI changes; device reruns needed for the capture
player, stager/codec, guide store and catalogue store fixtures.

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
build/test is running. Continue cloud review from iptv/wip.
See ../IPTV-CLOUD-HANDOFF.md for the entry point and versioned evidence.
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

## Consolidated 5-6 October changes and fixes

- Added a bounded, hash-bound TS inspection/extraction path after reproducing a
  missing final video sample in both Android and shipped Media3 extractors. The
  scoped bridge restores the complete inspected segment without upgrading libraries.
- Bound inspection and reads to immutable committed rows and exact owner/pins;
  changed, foreign or expired evidence cannot silently substitute another segment.
  Capture copying no longer blocks local snapshots/opens/pins behind its state lock.
- Added stable actual-PTS video/audio epochs across eviction/wrap and explicit
  configuration/timestamp boundaries. Pending seeks use their anchor in both
  directions; captured-tail selection remains distinct from wall-clock LIVE.
- Added transactional compressed-sample staging and retained Media3 metadata,
  including audio phase/preroll. No samples publish before verified EOF, shape /
  timing and cancellation checks succeed. Logical byte caps are not heap/codec proof.
- Added fresh space/allocation-unit/volume margins and per-write/index fail-stop
  fences; fixed the host allocation-unit/sector mismatch. These checks preserve
  last-good media and existing consumers, but do not preallocate OS space or certify
  device/USB/SMB margins and power-loss durability.
- Added asynchronous and incremental readers, exact-row loading, bounded tickets /
  batch caps, immutable cached timelines and snapshot/reentry fences. Atomic capture
  hints coalesce; live WAITING cannot become EOF or automatic local polling.
- Added actual growing MediaPeriod/SampleStream and BaseMediaSource components.
  Growth preserves cursor/epoch identity, frame seeks feed IDR/audio preroll, and
  explicit consumed-prefix transfer closes/refills off-thread without rebasing PTS.
  An initially empty running source remains unresolved instead of publishing EOF.
- Hardened stale/cancelled completion, failed/blocked input cleanup, one-closer
  retries, exclusive sample borrowing and playback callback coalescing/quiescence.
  Reservations stay held through uncertain period/source/reader closure; recorder
  fixtures remain independent. Queued-before-prepare metadata and init-reference
  cleanup were fixed. Native renderer shutdown and seek acknowledgement are separate.
- Consumer encoded-memory floors now apply before transport/consumer start. Actual
  transient/Java/native/graphics/decoder overhead remains a measured-admission gate.

Preceding overnight checks: full app compile451s, 317 IPTV JVM tests/38 suites with
zero failures/errors/skips (8.747s test /381s build), harness53s. Core207 overlaps
that run. Source callback tests use a synthetic poster and source owner tests stop
before Looper preparation. The real-stager/period/Looper/storage Android cases were
compile-only at that checkpoint. The newer AM9 continuation above executes these cases
and normalized buffer decoding; rendering, production physical capacity and
enabled controls remain unvalidated.
Actual admitted player/renderer integration, device validation, durable recording /
services/schedules/storage, providers/grouping/multiview/catch-up and remaining
Original/V2 guide/Home/Search/UX still require work. Detailed milestones follow.

## Historical overnight checkpoint summary

Overnight implementation checkpoint: 6ad3799. Temporary continuation was paused at
07:05 Brisbane before the 08:00 cutoff; both older jobs remain paused. Nothing was
published and the separate Fork checkout/prototype were preserved.

- Added actual BaseMediaSource plus an admitted OwnedCaptureConsumer over the
  reader: one IO metadata observer, one queued/in-flight playback callback and
  one exclusive epoch period. Initially empty running capture stays unresolved;
  exact UID/target and explicit boundary errors prevent automatic substitution.
- Source closure confirms observer/callback quiescence, actual period and
  BaseMediaSource lifecycle release plus reader/input closure before releasing
  ownership. Uncertain close keeps reservations and independent consumers.
  Preparation covers queued metadata updates; period close clears own format /
  init references. No source/period action certifies renderer shutdown or seek ack.
- Full app compile (451s), **317 IPTV JVM tests** (38 suites, zero failures/errors /
  skips; 8.747s test / 381s build) and headless harness builds (53s) pass. The 207
  unchanged core cases overlap this run. Seven host callback-gate and three actual
  pre-Looper source/runtime cases pass with synthetic poster/samples. They do NOT
  execute Android Handler or BaseMediaSource.prepareSource callbacks. Two actual
  Looper/Source/stager/period Android fixtures compile only.
- Targeted read-only AM9 query reports Asleep; no installation, instrumentation,
  power/CEC/settings/account/recording/provider operation, upgrade or control
  enablement. Executed source/period/device offsets/preroll, actual admitted
  player/renderers, pending seek/render acknowledgement and measured aggregate
  budgets remain required. Exact evidence is in the epoch-source report.

- Added an actual growing one-epoch MediaPeriod/SampleStream over cached verified
  staged batches. Live waiting yields NOTHING; only exact completed capture yields
  EOS. Growth preserves cursors, real video frame seeks and per-batch IDR/audio
  preroll, with stable absolute epoch PTS and explicit boundary/stop errors.
- One exclusive reader borrow prevents input/batch release and confirmed reader /
  runtime closure until period shutdown; independent consumers remain admitted.
  Explicit consumed-prefix transfer checks selected tracks finished those rows,
  keeps one row and closes/refills off-thread. Player loading callbacks cannot
  start automatic local tail polling. EOF retains ownership and never acknowledges
  a pending seek or decoder shutdown.
- Final app compile (454s), **307 IPTV JVM tests** (36 suites, zero failures /
  errors/skips; 8.453s test / 406s build) and final headless harness builds (49s)
  pass. The 207 unchanged core tests overlap this JVM run. Nine new period cases
  use real files, pins, queue, reader and runtime with SYNTHETIC encoded batches.
  Two actual-stager growth/period/EOS Android fixtures compile only; earlier
  pre-hardening app/harness outcomes and hashes are retained as superseded.
- Actual MediaSource/Looper dispatch, admitted player/renderer closure and exact
  seek/render acknowledgement, device offset/negative audio/preroll and measured
  aggregate budgets remain required. No device/provider operation, component
  upgrade or enabled control. See the epoch-period design and exact evidence.

- Added the actual persistent owned incremental reader over the bounded queue.
  One IO worker and metadata observer coalesce committed capture/explicit hints,
  build cached immutable Media3 metadata off the playback thread, fence stale /
  retiring views and retain ownership through failed release and blocked cleanup.
  Explicit epoch/terminal states survive release; no automatic retry or polling.
- Consumer declared memory floors now apply before new transport/consumer start.
  The incremental reader declares its encoded resident cap; measured transient,
  Java/native/renderer/decoder overhead remains required above that lower bound.
  Failed/under-reserved joins preserve other consumers and uncertain reservations.
- Final full app compile (457s), **207 core tests**, **298 IPTV JVM tests**
  (35 suites, zero failures/errors/skips; 8.251s test / 414s build) and final
  headless harness builds (62s) pass. Ten new reader fixtures use actual file,
  inspection, queue, runtime and transport notification paths with SYNTHETIC
  batches. Two actual-stager cached-timeline/epoch Android fixtures compile only.
- Actual dynamic MediaSource/epoch-period/sample binding, admitted player/renderer
  closure, normalized audio/preroll/device seek confirmation and measured aggregate
  budgets remain required. No device/provider operation, component upgrade or
  control enablement. Exact hashes and evidence are in the incremental-reader report.

- Added incremental exact-row inspection and TS sample staging as capture grows.
  The cursor preserves stable actual-PTS windows and distinguishes a waiting tail
  from successful completion, stopped capture, capacity, expiry and explicit epoch
  boundaries. Ticket and tail-anchor pins remain owned until confirmed release.
- The queue checks the next worst-case encoded batch/slot cap before loading,
  publishes only successful transactional staging, and retains arrays/pins through
  cancellation and uncertain cleanup. Callback reentry cannot bypass caps or close
  active loading. No automatic polling/retry, skipped row or epoch crossing.
- Final full app compile (448s), **204 core tests**, **285 IPTV JVM tests**
  (34 suites, zero failures/errors/skips; 7.730s test / 384s build) and final
  Android harness builds (31s) pass. Thirteen new core cases inspect original TS
  fixtures; seven JVM queue cases use synthetic compressed batches and real pins.
  Three real-stager growth/metadata, epoch and cancellation Android cases compile
  only. Final evidence distinguishes superseded pre-hardening/test-fixture builds.
- This is blocking local loading/staging, ready for a future governed worker/source
  binding. Actual dynamic MediaSource/epoch-period callbacks/sample delivery,
  renderer/player closure, normalized audio/preroll and measured aggregate budgets
  remain gates. No device/provider operation, dependency upgrade or capture-control
  enablement occurred. Logical encoded-byte caps do not certify heap/codec memory.

- Added an actual asynchronous pinned-reader consumer for SharedCaptureRuntime.
  It commits/stages one exact pending seek, fences stale/cancelled completions,
  keeps a successfully staged owner before cancellation checks, and confirms
  worker/input/pin closure before releasing ownership. Repeated close timeouts
  share one closer; failed cleanup retains the exact handles for explicit retry.
- Final full app compile (545s), **191 core tests**, **265 IPTV JVM tests**
  (32 suites, zero failures/errors/skips; 7.011s test / 391s build) and final
  harness builds (33s) pass. Twelve new JVM cases use real files, inspection,
  seeks and shared runtime with synthetic samples. Failed reader closure retains
  its memory/pin reservation and preserves an independent recording consumer.
  Two actual-stager Android reader fixtures compile only; no device or provider
  operations occurred in this phase. Exact evidence is in the pinned-reader report.
- READY borrowing is separate from player seek/render acknowledgement and decoder
  closure. The reader opens no player, codec, audio/display or network and is not
  a dynamic live MediaSource. Measured aggregate budgets, renderer offsets/preroll,
  dynamic source binding of waiting/terminal/epoch outcomes and durable recording remain required before
  enabled controls. JVM reservation/recorder fixtures do not certify those gates.

- Added an actual finite Media3 MediaPeriod/SampleStream for one inspected,
  verified and pinned staged segment. Reads support format, peek/omit, encoded
  payload/time/keyframe and EOF; seeks return actual video-frame positions and
  retain initial-IDR/audio preroll. Playback-thread/track ownership is fenced,
  EOF keeps pins, and failed input closure remains reserved for a later retry.
- Final full app compile (503s), **191 core tests**, **253 IPTV JVM tests**
  (31 suites, zero failures/errors/skips) and final Android harness builds (36s)
  pass. Nine new JVM cases use synthetic encoded payloads with real verified file
  pins and Java Media3 interfaces. Two new Android fixtures compile but remain
  unexecuted because AM9 is Asleep; only targeted read-only state checks occurred.
  The headless harness has no target permissions/activities and reuses unchanged
  shipped player/data-source libraries and the existing decoder dependency.
- This finite period does not establish live-tail/epoch loading or a MediaSource.
  Runtime renderer preroll discard, normalized negative audio, player/decoder
  closure and aggregate memory admission remain required. It never acknowledges
  a pending seek or enables capture controls. Evidence/hashes are in the new
  pinned-period design and validation report.

- Added fresh physical free-space, allocation-unit and volume checks with explicit
  caller margins and worst-case file/index overhead. Governed capture sharing now
  rejects unguarded or under-reserved stores before starting transport/consumers.
  Per-write and index-publication checks preserve committed media and stop capture
  without retries; failed joins preserve existing recording consumers and retain
  reservations through uncertain closure. These checks are not OS preallocation.
- Final app compile, **191 core tests**, **244 IPTV JVM tests** (30 suites, zero
  failures/errors/skips) and final Android harness builds pass. A real host
  FileStore fixture ran in a small task temporary directory. Two Android statvfs
  fixtures remain unexecuted; no device or provider commands occurred. Device
  margins/capacity, USB/SMB and power-loss durability are not newly certified.

- Added bounded transactional compressed-sample staging: output stays private until
  pinned length/hash verification, supported formats, sample counts/keyframe/PTS
  checks and cancellation succeed. One video reference preserves audio phase,
  including preroll; limits cover encoded data, sample counts and dimensions.
- Added actual Media3 Window/Period metadata over retained staged batches with
  stable epoch identity, eviction offsets, audio-tail duration and explicit
  discontinuities. This adapter does not yet load or play samples.
- Final app compile, **178 core tests**, **230 IPTV JVM tests** (28 suites, zero
  failures/errors/skips) and final Android harness builds pass. Six new Android
  staging cases remain unexecuted because AM9 was observed Asleep; no install or
  wake operation occurred. JVM timeline fixtures use synthetic batch metadata.
  Aggregate heap/codec admission, dynamic MediaSource/epoch-period loading, renderer preroll and
  actual player integration remain required before controls can be enabled.

- Retained-media continuation adds ephemeral inspection evidence tied to committed
  rows, pinned hash-verifying inputs, stable actual-PTS/audio epochs and pending
  seek/explicit captured-tail policy. The 171-test core suite, full app compile,
  **217 IPTV JVM tests** and Android harness build passed. The retained-media
  evidence report records exact outcomes; no new device run is claimed. Media3
  publication, player commands and decoder preroll remain to be integrated.

- New internal components inspect a bounded AVC/AAC MPEG-TS profile, expose explicit local DATA/WAITING/ENDED/EXPIRED/DISCONTINUITY/STOPPED states, and allow local reads/pins during slow capture input. Pins are rechecked at publication.
- Reproduced a missing final video sample in AM9's platform extractor and the shipped Media3 HLS extractor. A scoped, hash-bound local extraction bridge restores the final sample for inspected complete segments; bundled libraries and dependency versions are unchanged.
- Preceding TS entry checks: **150 core JVM tests** and **214 AM9 tests** passed. Three independent segments decoded all 50 video frames each and 95/94/94 audio frames. Real capture/store/local-read integration decoded 150 video and 283 audio frames. FFprobe/strict FFmpeg independently agreed. Final full app Kotlin compile, **196 IPTV JVM tests** and the final annotated Android harness build also passed. The 214-test device run precedes only two UnstableApi annotations; final APKs were not reinstalled. Suites overlap.
- Structural inspection is not a general bitstream safety certificate. Media3 metadata and finite sample delivery now exist internally; dynamic loading, governed playback and durable recording remain unfinished. Physical observation fences now exist internally; device margins and behavior require validation. Pause/timeshift/recording controls remain disabled.

The preceding HLS milestone:

- Implemented internal capture: bounded atomic spool and retention pins, independent viewer/recorder leases, one-body-at-a-time ingestion, finite local snapshots, and a bounded single-media-playlist HLS source with dedicated HTTP ownership.
- Latest fixes prevent capture from silently skipping missing media or accepting changed overlapping segments; recover responses lost on cancellation; retain failed manifest-close handles; and prevent swallowed/no-op HTTP close from falsely releasing ownership.
- Latest measured checks: full-app Kotlin compile, **176 IPTV JVM tests** and **190 Android backend tests** passed. Focused 130 core/10 HTTP checks also passed; all suites overlap. No new capture prototype APK was installed.
- These remain internal components. Pause/timeshift/recording, decoder-safe seeks and local live-player integration are not enabled. Broader capture protocols, durable services/schedules/storage, Stalker/automatic Xtream EPG, multiview/catch-up and final guide/Home/Search/UI remain outstanding.
- Component upgrades are a separate handoff to another agent; no dependency versions changed in this IPTV work.

## Live TV, sources and guide foundations

- Multiple independent M3U and Xtream Codes API live sources with Nuvio Original/V2 source forms, masked credentials and profile isolation.
- Bounded playlist parsing and refresh, stable source-scoped channel identities, favourites, hidden/custom-name overlays, transactional last-good retention and shrink review.
- Encrypted local credentials/catalogue and profile deletion cleanup. Xtream authentication, active-expiry checks, category/live-channel refresh and encoded credential components; no speculative media fetch on refresh.
- Multiple XMLTV HTTP/HTTPS feeds, gzip input, bounded XML parsing, cache validators, source/feed linking, manual channel mapping and precise-time programme summaries. XMLTV supplies listings; it is not a stream protocol.
- First Nuvio Live TV screen: preview, expanded video, 24-channel pages, source switching, favourites and focused-channel programme information. Focus does not open a stream.
- Foreground ExoPlayer playback with conservative provider/decoder/memory reservations; release must confirm before replacement. Background/profile/navigation cleanup and no automatic resume.
- Progressive MPEG-TS and HLS, including tested sliding TS-segment and byte-range fixtures. Durable per-channel Auto/HLS/MPEG-TS choice supports extensionless HLS when explicitly selected.
- Disabled automatic failed-load retries and alternate-HLS-rendition fallback. Fixed HTTP/1.0 metadata stale connection handling without enabling retries.

## Earlier HUD, guide and track development milestones

- IPTV HUD using the fork's existing HUD presentation with a dedicated live sampler: source video/audio format, buffer ahead, manifest live offset when available, HTTP body transfer rate/bytes, open requests, prepare-to-first-frame tune time, rebuffer count/time and renderer dropped buffers.
- XMLTV files can be selected through Android OpenDocument with a persistent read grant and refreshed through the bounded transactional importer. Malformed or inaccessible documents preserve last-good rows. Devices without a document picker show an explanatory message; AM9 currently has no system picker.
- Guide URLs can follow bounded HTTPS cross-origin redirects; downgrade and URL userinfo are rejected. Destination cache validators are neither forwarded nor stored against a short URL. Media redirect policy is unchanged.
- Native Nuvio audio/subtitle track dialog with Automatic, subtitle Off, current-track selection and player-reported unsupported tracks. Stale selections are checked against the current player; choices reset with the player. Controls alone are not codec certification.
- Shared HUD presentation now supports both the existing player and IPTV. Live counters reset per acquisition, separate startup from rebuffering, and report ongoing stalls and idle transfer rates. Toolbar controls wrap to accommodate the additional actions; remote/layout verification remains pending.
- Statistics never include provider URLs/credentials. Transfer rate is measured body consumption, not a speed test; live offset is not glass-to-glass latency; source frame rate is not HDMI output rate.
- Shared capture foundation now includes independently closing viewer/recorder ownership, a one-segment-at-a-time ingestion loop and a pinned local snapshot reader. These components are not yet enabled in the foreground player and do not constitute playable timeshift or recording.

## Correctness and reliability improvements

- Foreground replacement waits for both decoder and HTTP closure. Cancellation and stale screen owners cannot admit or stop the wrong session; uncertain releases retain reservations.
- Fixed two independently reproduced transport issues: stale HTTP/1.0 metadata connections and alternate HLS rendition requests after a failed segment despite disabled retry delays.
- Stream-format overrides persist through refresh/restart and schema migration, remain source/profile scoped, and invalidate stale browse cursors. Saving a format does not open media.
- Redirected guide refreshes isolate cache validators from the short URL, and inaccessible/malformed local guide replacements retain last-good programme data.
- Improved fixture tooling to reject stale UI dumps after a failed device capture. Added a synthetic document provider/picker for isolated grant testing; it does not supply a shipping file picker.

## Requested work still outstanding

- Complete local-file UI validation and an import alternative for TVs without a document picker; finish automatic Xtream EPG integration. The supplied tinyurl.com/epg-ss11 returned 404 for both bounded public HEAD and GET checks on 5 October; its guide contents are not validated.
- Stalker Portal source adapter and independently validated authentication/channel handling.
- Complete audio/subtitle preferences and representative rendering checks for subtitles, closed captions, DVB subtitles and teletext. Track-selection UI is implemented; these formats are not all certified.
- Connect the implemented shared-capture infrastructure to local pause/resume and seekable timeshift; implement durable recording/schedules to internal storage, USB and SMB.
- Bind the now device-tested inspection/stager/growing-period/MediaSource path to admitted real player/renderers and exact seek acknowledgement; validate renderer discard and measure aggregate memory/device storage margins before enabling controls. Broaden the narrow capture protocol/profile only with supported entry points and ownership.
- Multiview with an independent source/playlist per pane, shared account groups and aggregate device/provider limits; provider catch-up.
- AFR/display handoff, integrated VOD/trailer/IPTV resource ownership, full guide grid, Home/Search, source/feed management, all supported locales and appearance/accessibility checks.

## Validation and known limitations

- At `0211656`: **112 JVM IPTV tests and 133 AM9 backend fixtures passed** (overlapping suites); full compilation/prototype assembly passed in 8m49s. Device fixture suite passed in 29.168s without network permission. Logs: iptv-core/hud-guides-build.log and hud-guides-am9.txt. Evidence: IPTV-HUD-GUIDES-VALIDATION-20261005.json. Previous video evidence remains in IPTV-HLS-VALIDATION-20261005.json.
- HUD/track/document checks remain pending after HDMI-sleep interruptions. The historical backend run observed AM9 Awake; the latest 6 October targeted query observed Asleep. Neither certifies current screen availability or UI paths. No HDMI/CEC/settings changes. Initial HUD-build Live TV screen rendered before the earlier sleep.
- AM9 controlled video/UI checks cover progressive TS, sliding HLS, byte ranges, extensionless format selection, failure handling, explicit activation, replacement, favourites/paging and background cleanup. No real provider stress or credential copying.
- Synthetic 640×360/25 FPS H.264/silent AAC evidence is not audible output, HDR/Atmos, UHD decoder capacity or broad provider/codec certification.
- Current policy allows one foreground IPTV decoder and one acquisition for unknown/shared account capacity. These are estimates, not measured device limits.
- Media redirects remain rejected. No DRM, encrypted HLS, fMP4 or separate-audio certification. No finished timeshift/recording/multiview yet.
- Expanded video retains the prototype toolbar. English fallback strings remain; source form focus with an open IME needs further device checks.
- Original dirty fork and device apps/accounts/preferences/recordings are preserved. Prototype package: com.nuvio.iptv.prototype. Nothing published.

## Change history

196dcf4 ingest/admission; 9122fc6 catalogue/M3U transport; b8e60e9 XMLTV persistence; 84924e3 guide HTTP/cache; 53bd036 bounded browse/mapping; 7283bf5 source forms/profile cleanup; 7ec290a governed live playback; e636438 Xtream setup/HTTP1.0 fix; a9871c7 durable stream format/HLS fallback fix; 0211656 IPTV HUD/document guides/secure redirects/native tracks; 8f1316e bounded capture spool/reader retention/atomic publication; 4c3e0b0 shared capture ownership/sequential ingestion/finite local snapshot; eaa43e5 bounded HLS capture and HTTP closure fencing; 43b1dff TS inspection/local extraction bridge; 25395a2 pinned inspection/sample epochs/seek policy; 4606418 transactional staging/shared PTS/Media3 metadata. ae0c08b physical-space/unit/volume fences; 6842df1 finite pinned period; a960696 async exact-seek reader; 0b82357 incremental inspection/TS queue; 34a4937 governed incremental reader; ed528c6 growing epoch period/exclusive borrow; 6ad3799 owned MediaSource/playback callbacks; 6f4a01d confirmed overnight pause and summary.

## Upstream review

Reviewed draft NuvioMedia/NuvioTV PR #3788 at e10c639: reproduced a preview direction-reversal seek error and a live-label delay discrepancy; documented retention/recovery concerns. Not imported. See IPTV-UPSTREAM-PR3788-REVIEW.md. Forthcoming timeshift tests must distinguish RAM load targets, actual playable windows and local capture retention.

## Capture foundation — historical internal milestone

- Added an exclusively owned, bounded segment spool with synced complete-segment/index publication through atomic moves, close/reopen recovery and scoped orphan cleanup. It refuses nonempty unowned directories, unexpected files and managed symlinks. Retained logical bytes, segment sizes/count and index parsing are bounded.
- Pause/recording anchors and open readers prevent eviction of retained media. Capacity exhaustion reports backpressure; gaps and discontinuities remain separate in actual retained bounds. Cancellation, oversized input and read failure before publication preserve committed data; failed cleanup blocks the next allocation until resolved. Closure is refused while readers/anchors remain.
- At `8f1316e`, seven focused filesystem tests passed separately on JVM (0.223s) and AM9 (0.39s), using the actual production class. The full 112/133 suites were not rerun at that checkpoint. The latest shared-capture section below records the subsequent full reruns. See IPTV-CAPTURE-STORE-VALIDATION-20261005.json for the historical evidence.
- Capture-to-player integration, durable schedules/leases and internal/USB/SMB recording are still unfinished; the latest internal shared/HLS transport is implemented below. File sync/atomic rename testing does not establish physical-power-loss durability. This store has not yet been included in the installed prototype APK. Extra staged-segment/index/filesystem space, physical disk allocation and decoder-safe seek entry points still need the transport/storage integration.

## Continuation record

Complete implementation context, reproduction locations, device cleanup state, remaining work and fresh-session instructions are saved in [the current fresh-session handover](IPTV-NEXT-SESSION-HANDOFF-20261006.md). The shared capture continuation builds on `27f78ea`; nothing has been published.

## Shared capture continuation — historical internal components

- Added a runtime that owns one producer/store across separate viewer and recording consumers. Closing a viewer leaves remaining consumers running. Uncertain decoder/reader closure retains that consumer's reservation; uncertain producer/store closure retains capture memory, account and full spool storage. Stale consumer tokens cannot close replacement acquisitions. Foreign acquisitions cannot be shared without their actual pipeline.
- Capture storage admission includes retained bytes, one staged segment and an explicit overhead margin. This remains a logical budget, not measured physical disk usage or a free-space guarantee.
- Added sequential segment ingestion with bounded store writes, body closure before the next pull, explicit backpressure/failure states and no automatic retry. Cancellation closes late bodies without publishing them; failed generic body closure remains retryable. The later HLS milestone adds a narrow protocol adapter; progressive TS and broader capture protocols remain unfinished.
- Added a finite local snapshot byte reader that pins before reading, crosses only contiguous segments, excludes future appends and retains its pin until explicit close. This is not yet a live-tail Media3 reader, a keyframe-safe seek window or a finished export feature.
- Twenty-four new regression tests cover sharing, final-consumer closure, cancelled joins/stops, failed construction/start/closure, foreign acquisitions, snapshot boundaries/retention, oversize input, backpressure, late bodies and blocked cleanup retries. Thirty-one focused JVM tests, including the original seven store tests, passed in 1.112s. Full app compilation and **143 JVM tests** passed; the final Android backend suite passed **164 tests in 30.233s** with no network permission. The suites overlap. Memory-related build/test-worker startup failures and the successful bounded reruns are recorded in the evidence report.
- AM9 was not woken. Temporary test packages were removed; pre-existing reverse8765 remains. The installed prototype remains the older HUD build. No new playback, UI, subtitle, document-picker, real-provider, power-loss or USB/SMB validation is claimed.
- See [the ownership/reader design and remaining integration requirements](IPTV-SHARED-CAPTURE-DESIGN.md) and `IPTV-SHARED-CAPTURE-VALIDATION-20261005.json`.

## HLS capture continuation — historical protocol milestone

- Added an internal media-playlist parser, sequential HLS capture source and
  dedicated HTTP client. One body is opened at a time; changed or missing media
  halts capture, with paced bounded live reloads and no failed-request retry.
- The initial subset rejects master/rendition selection, encryption, maps,
  ranges, gaps, partial segments and cross-origin media before fetching those
  resources. It is not general HLS support or an enabled player feature.
- Cancellation fences late connects and reclaims responses never delivered to
  their caller. Manifest bodies remain owned on failed close. Uncertain underlying
  HTTP body closure cannot become a successful release through a later no-op close.
- Manifest-derived times and discontinuities are retained as metadata. No codec,
  init/keyframe, actual PTS or decoder-safe seek claim is made.
- Final checks: 130 pure-core tests, 10 host HTTP fixtures and 190 Android backend
  tests (35.827s). Suites overlap. The host HTTP integration captured synthetic
  byte resources and read committed bytes locally; it did not validate playback.
- Android harness built with 1 GiB heap. Its test packages have no INTERNET
  permission and were removed afterward. AM9 was observed awake; this task did
  not wake it, reinstall the prototype or change its settings/accounts.
- Full-app build outcomes are recorded separately in the evidence report; an
  initial 3 GiB run failed from JVM native-memory allocation. No unrelated daemon
  was stopped and no system memory setting was changed.
- Decoder-safe media validation, local live-reader/player integration, pause,
  recording/services/schedules and physical/USB/SMB storage remain outstanding.
  See `IPTV-HLS-CAPTURE-DESIGN.md` and `IPTV-HLS-CAPTURE-VALIDATION-20261005.json`.

- Final whole-app checks passed: full application Kotlin compilation (6m28s)
  and all **176 IPTV JVM tests**, with zero failures/errors/skips. These overlap
  the focused host and Android suites; counts are not independent.
- Successful compilation used a 3 GiB heap, 512 MiB metaspace, SerialGC,
  128 MiB code cache, two reported processors, one worker and the in-process
  compiler. The final JVM run used the same overhead limits with a 1 GiB heap.
  These were command-line flags only; project/system memory settings and
  unrelated daemons were unchanged.

## TS entry and local-reader continuation

See [the controlled-media/local-reader design](IPTV-TS-ENTRY-DESIGN.md) and
`IPTV-TS-ENTRY-VALIDATION-20261005.json` for supported profile limits, exact hashes,
reproduced failures, decoding evidence and remaining integration gates. All suites
overlap. Header inspection, independent fixture decoding and an internal extraction
bridge do not certify arbitrary provider content or constitute an enabled player.

Capture no longer holds the store state monitor during source copying/segment sync.
Snapshots, opens and new pins work while a source is stalled; publication honours
pins acquired during that copy. Index publication/retirement still involve bounded
local I/O under the monitor. The live reader retains its anchor while waiting or at
a terminal boundary, and never interprets a failed producer as successful EOF.

AM9 tests ran headlessly without waking the device or replacing the older prototype.
Temporary validation packages were removed. The final reverse list was empty;
this continuation created or removed no reverse mapping, so the historical 8765
mapping is not claimed present. Accounts, settings, recordings and both paused
automations were left alone. No provider requests or dependency upgrades occurred.

## Retained inspection and seek policy continuation

- Header evidence now belongs to one committed file row and index/store owner.
  Expired or foreign evidence cannot open another file. Cache entries hold no pins;
  opening media pins its exact file and successors until the caller closes it.
- Inputs recheck byte count/hash through verified EOF, including skipped bytes.
  Early close/cancellation does not certify media, and EOF keeps ownership until
  explicit close. The inspector remains a header check with a narrow codec profile.
- Actual video PTS/cadence and audio phase define stable sample epochs. Eviction and
  an empty retained snapshot keep the epoch origin; sequence/capture/configuration
  or timestamp breaks produce explicit epochs. Published metadata is bounded.
- Seek preview uses the pending target for both directions until exact player
  acknowledgement. Commit rejects stale/expired targets without selecting another
  file. Explicit return-to-captured-tail selects the newest verified header sample;
  it does not establish wall-clock LIVE or perform player/decoder commands.
- Measured: 171 core tests, full app compile, 217 IPTV JVM tests and Android harness
  build passed. Core/JVM suites overlap. No new device/decoder execution is claimed.
  At that checkpoint, transactional staging, actual Timeline/MediaPeriod and
  governed loading remained next; newer checkpoints now add staging, metadata,
  finite period and async reader delivery. Dynamic source/player, runtime preroll
  and aggregate/device gates still remain.
