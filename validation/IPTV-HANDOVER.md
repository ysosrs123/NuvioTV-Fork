# Nuvio IPTV — handover

Branch: `iptv/player-binding` (includes `main` as of 6 October 2026).
Last device-validated commit: `b68985a`. Everything after it is host-tested and CI-built
(`def2b19`), not device-tested; device reruns are listed under "Next steps".
Related: [progress](IPTV-PROGRESS.md), [draft release notes](IPTV-RELEASE-NOTES-DRAFT.md),
[code review](IPTV-CODE-REVIEW-20261006.md), [reference app notes](IPTV-UX-REFERENCE.md),
[player validation report](IPTV-CAPTURE-PLAYER-VALIDATION-20261006.json).

## Status

- Live TV (M3U/Xtream sources, XMLTV guides, foreground playback, HUD, track
  dialog) works on the AM9 prototype build. Capture/timeshift/recording code exists
  below the UI but its controls stay disabled.
- IPTV settings are visible only in the `iptvPrototype` flavour
  (`FEATURE_IPTV_ENABLED`); the `full` flavour hides them.
- The capture player is wired to a real ExoPlayer in test fixtures. The cause of the
  two AM9 player-fixture timeouts is fixed in source; the device rerun is pending.

## CI build — 6 October 2026

- GitHub Actions run 37419887574 (`PR Full Debug Build`, variant `iptvPrototype`,
  commit def2b19) is green: full app compile including the Compose/Hilt UI changes,
  IPTV core and data-layer unit tests under Gradle, and the APK. This is the first
  Gradle build since `b68985a`. Not device-tested yet.
- `LocalTsSegmentExtractorTest` skips under Gradle (stubbed `android.util.SparseArray`);
  `LocalTsSegmentExtractorAndroidTest` covers it on device.
- The app builds per-ABI APKs only (`isUniversalApk = false`); the workflow uploads
  `app-<variant>-arm64-v8a-debug.apk`.
- The prototype installs as `com.nuvio.iptv.prototype` ("Nuvio IPTV Prototype")
  alongside the normal app; debug builds add no suffix. CI signs with a throwaway key, so a prototype signed with
  another key must be uninstalled first (this clears its sources).
- Logs: `get_job_logs` with `return_content` shows the last 8 KB; the workflow prints
  a filtered error summary on failure. Without GitHub tools, run status is readable
  from the public REST API, but logs and re-runs need the user.

## Device findings — 6 October 2026

First AM9 run of the CI-built prototype with a real Xtream account (13,740 live
channels, 4.8 MB list, 145 MB provider guide taking over two minutes to download).

- A wrong username produced the generic failure message. Source errors now name the
  failed step (address, network, HTTP status, redirect, size, format, sign-in) and the
  Xtream log line includes the HTTP status. Server and username are no longer masked;
  the password has a Show/Hide toggle; missing `http://` is added; credentials are
  trimmed; a new source loads its channels on save.
- Refresh sat on "Working…" for minutes and no channels appeared. Causes found in
  code: one Keystore operation per channel row, and the automatic provider guide
  (capped at 64 MB and a 60 s call timeout) ran inside the same operation. Fixed:
  envelope sealing (`EnvelopeIptvSecretBox`), Xtream rows stored without the login
  (`IptvXtreamClient.streamUrl` adds it at tune time), provider guide refreshed in
  the background with 512 MB, 50,000-channel, 2,000,000-programme limits and a
  15 min call timeout. Why no channels appeared is not yet confirmed on device.
- Pending on device: install a build of 6de391f or later (CI run 37444353656 is green;
  local Windows builds hit out-of-memory with the 6 GB Gradle heap), then time the
  Xtream refresh, confirm channels appear and the provider guide imports.
- The Live TV and Sources screens need a full visual and usability redesign
  (user feedback); planned after the device fixes.

## Small open items

- The automatic Xtream guide feed stores `xtream-guide:<sourceId>` as its endpoint.
  The feed list and edit form must show it as an automatic provider guide and not
  offer endpoint editing.
- `suggestAccountGroups` distinguishes only Xtream and M3U; Stalker sources should
  group by portal host and MAC (as Xtream with the MAC as the user).
- `IPTV-CAPTURE-PLAYER-VALIDATION-20261006.json` source hashes predate later commits;
  refresh them with the next recorded validation.
- The workflow changes and `tools/iptv-host-tests` ship with the squash merge.

## Next steps

1. Install the CI APK on the AM9 and check Live TV. Build `tools/iptv-device-tests`
   locally (not built in CI).
2. On the AM9 rerun: `CaptureVideoPlayerAndroidTest` (both cases),
   `LocalCaptureSampleStagerAndroidTest`, `TsCaptureDecodeAndroidTest`,
   `IptvGuideStoreTest`, `IptvCatalogueStoreTest`, then the full fixture suite.
   A player timeout now fails with a state dump (player, window, decoder counters,
   reader, transport, renders).
3. Time a large catalogue refresh on the AM9 (review item 12) before redesigning
   per-row Keystore sealing.
4. Then measure aggregate memory and storage margins, validate renderer preroll
   discard and seek acknowledgement, and only then enable capture controls.
5. Once steps 1–2 pass, squash-merge `iptv/player-binding` into `main` as one commit
   and delete the `iptv/wip` and `iptv/player-binding` branches.

## Logic layer added for the next UI work — 6 October 2026

Host-tested (core 238, data-layer 121); the screens that use them are not built yet.

- Guide grid: `GuideGrid.kt` lays out a slot-aligned time window per channel
  (clamping, overlap trimming, no-information gaps, open-ended programmes, focus time
  anchor). `IptvBrowseRepository.guideRows` loads a page of channels into rows.
- Automatic Xtream guide: after a published Xtream refresh the source gets a linked
  feed whose endpoint is `xtream-guide:<sourceId>`, resolved at refresh time to the
  provider's `xmltv.php` from the encrypted connection. Large provider guides may hit
  the 64 MiB parse budget.
- Account groups: catalogue schema v5 adds source positions and per-profile account
  groups (label, 1-16 streams). The live runtime applies the group limit at open.
  `suggestAccountGroups` proposes groups by provider host (and Xtream user).
- Stalker Portal: `STALKER` source kind (MAC in the connection's username field),
  handshake/profile/genres/channels refresh, `create_link` at tune time. Profile
  status codes other than 0 fail closed; real portals still need checking.
- Local guide import: `.xml/.xmltv/.xml.gz/.gz` files in the app's `iptv-guides`
  folder on internal storage or USB are listed and used via `file:` endpoints;
  paths outside those folders are refused.

UI still needed for these: guide grid screen; Stalker option and MAC field in the
source form; account group editing and source ordering; local guide file chooser;
showing the automatic Xtream guide in the feed list.

## Review fixes — 6 October 2026

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

Item 12 fixed after device testing (see "Device findings"). Item 20 resolved by
the `FEATURE_IPTV_ENABLED` flavour gate. No change by design: 7, 8, 18, 21 (reasons
in the review).

Host verification (no Android SDK): core 222/222; data-layer JVM 117/117 across every
com.nuvio.tv.data.iptv suite (real stager/extractor, MockWebServer HTTP clients).
Each fix with a test was checked to fail without its change. All 18 IPTV androidTest
classes and IptvLivePlayback compile against the shipped Media3 classes with stubs.
Not compiled here: Compose/Hilt UI files (navigation, ViewModels, settings) and
ProfileManager; not run: full app compile, Gradle unit tests, device instrumentation.
Run those locally before trusting UI changes; device reruns needed for the capture
player, stager/codec, guide store and catalogue store fixtures.

## Player binding — 6 October 2026

Branch iptv/player-binding, derived from iptv/wip 8b84c11. The last device-validated
checkpoint remains b68985a. Details, hashes and limits:
[IPTV-CAPTURE-PLAYER-VALIDATION-20261006.json](IPTV-CAPTURE-PLAYER-VALIDATION-20261006.json);
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

Host evidence: core 211/211 pass with the existing runner and pinned Kotlin 2.3.0.
A host JVM harness ran the 13 data-layer capture suites (66 cases: 64 existing plus
two new regressions) against the shipped Media3 AAR classes, Media3 1.8.0 lib-decoder
built from source and a default-value android stub generated from android-all. The new
cases fail without their changes and pass with them. The AM9 fixture compiles against the
same classes with a stub InstrumentationRegistry. These are not Gradle/AGP builds: the
Android SDK host is blocked, so no full app compile, full 317-case JVM
run, harness APK or device execution has been done for this change.

Next local steps: full app compile, full IPTV JVM run, harness build, then the two
CaptureVideoPlayerAndroidTest cases on AM9 using the existing README commands and
device rules. Controls stay disabled. Rendering, preroll discard, seek acknowledgement,
blocked-release retention and measured memory remain unclaimed until those pass.

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
| b68985a | ID3 exclusion, AM9 capture validation | IPTV-EPOCH-DEVICE-VALIDATION (20261006) |

Earlier architecture: eaa43e5 HLS source/HTTP fences; 4c3e0b0 shared capture;
8f1316e store; 0211656 HUD/document guides/secure guide redirects/native tracks.
Earlier ingest/guide/playback reports are the *-20261005.json files in this folder.

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

## Remaining scope

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
4. Stalker Portal, automatic Xtream guide and account groups exist as logic (see
   above); remaining: their UI, feed/source management, mapping/facets/paging and
   rejected-candidate review. Preserve overlays/profile isolation.
5. Different-source multiview, catch-up and aggregate VOD/trailer/IPTV ownership,
   single audio/display owner, provider limits and AFR/display handoff.
6. Full guide grid, Home favourites/sports/recordings, Search, immersive watch-first
   Nuvio Original/V2 UX, source-form IME/Save focus, HUD/replacement/background,
   remote audio/text select/off/render, preferences/locales/accessibility and
   representative subtitle/CC/DVB/teletext/hardware validation.

Do useful independent work around device/provider/UI blockers; do not repeat
finished competitor/upstream research or silently fetch real providers.

## Build and test

Set `JAVA_HOME` (JDK 17+), `ANDROID_HOME` and, for the core runner,
`GRADLE_MODULE_CACHE` (a Gradle `modules-2/files-2.1` cache holding the pinned jars).

```
./gradlew :app:compileFullDebugKotlin
./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.core.iptv.*' --tests 'com.nuvio.tv.data.iptv.*'
./gradlew -p tools/iptv-device-tests assembleDebug assembleDebugAndroidTest
python3 scripts/check_iptv_core.py
```

Low-memory machines can add `--no-daemon --max-workers=1` and a smaller
`-Dorg.gradle.jvmargs` heap. Do not upgrade dependency versions to make a check pass.
Instrumentation steps are in `tools/iptv-device-tests/README.md`.
Without the Android SDK (for example in a cloud session), `python3
tools/iptv-host-tests/run.py` runs the core and data-layer JVM tests and compile-checks
the device tests; see its README. The `PR Full Debug Build` workflow can build the
prototype APK on GitHub Actions (manual run, variant `iptvPrototype`).

## Device test rules

- Use only the dedicated AM9 test device; pass its serial explicitly to every adb call.
- Check its state first. Do not wake the TV or AVR, or change power, CEC, settings,
  accounts or recordings.
- Install only the two validation packages and uninstall them afterwards. Leave the
  installed prototype and comparison apps alone.
- Use synthetic fixtures and local fixture servers only; no provider traffic or
  credential copying. Black HDMI-sleep screenshots are not playback evidence.
- AM9 has no system document picker; the synthetic picker in the test APK covers
  the grant flow only.

## Fixtures

`app/src/test/resources/iptv-ts` holds the synthetic TS segments used on the JVM and
device. `tools/iptv-device-tests/validate_capture_media.py` independently probes and
decodes them; generate new fixtures only into an empty directory.
