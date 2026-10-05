# IPTV implementation progress

Updated 6 October 2026, Australia/Brisbane.

## Owned epoch-source checkpoint - current validated continuation

From clean ed528c6, added actual BaseMediaSource/OwnedCaptureConsumer over the
incremental reader, one metadata observer and at most one queued/in-flight playback
callback. Exact cached UID/period ownership, unresolved initial running tail and
explicit boundaries preserve semantics. Source close joins observer and confirms
callback quiescence, period/BaseMediaSource caller release and reader/input closure;
uncertain close retains runtime reservations and independent consumers. Period
preparation covers queued metadata updates; close clears own format/init references.

Full app compile passed in 451s, all 317 IPTV JVM tests in 38 suites passed with
zero failures/errors/skips (8.747s test / 381s build), and headless harness builds
passed in 53s. Core207 is unchanged and overlaps the full JVM suite. Ten new host
cases comprise seven callback-gate cases with synthetic asynchronous poster and
three actual pre-Looper MediaSource owner/runtime cases with synthetic samples.
They do NOT exercise BaseMediaSource.prepareSource or Android Handler callbacks.
Two actual Looper/Source/stager/period Android fixtures compile only. Read
IPTV-EPOCH-SOURCE-DESIGN.md and the exact source/component/APK/log/XML report first.
All commands completed; no task build remains running.

Read-only get-state/targeted power query on authorized AM9 at 20:52:43 UTC reported
device/Asleep. No connect/install/instrumentation, wake/power/CEC/account/settings /
recording operation, provider traffic, component upgrade or control enablement.
Actual executed Source/Looper/period/normalized audio/offset/preroll tests, admitted
real player/renderer ownership and exact pending seek/render acknowledgement,
measured aggregate memory/storage margins and durable/provider/UX scope below
remain required. Temporary continuation is PAUSED, confirmed at 07:05 Brisbane before the 08:00
cutoff; both older jobs remain PAUSED. Implementation checkpoint is 6ad3799.

## Growing epoch-period checkpoint - historical validated continuation

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

## Incremental sample loading — historical validated checkpoint

From a960696, added exact-row inspection/PTS-window cursor and actual bounded TS
queue. It distinguishes waiting/complete/stopped/capacity/expiry/epoch/failure/cancel;
retains ticket/anchor pins; checks next worst-case encoded batch/slot caps before
opens; and fences reentrant mutation/closure. Failed/cancelled loads publish nothing,
while failed cleanup retains handles and arrays until explicit confirmed retry.
Final app compile (448s), 204 core tests (2.186s), all 285 IPTV JVM tests (34 suites; zero
failures/errors/skips; 7.730s test / 384s build) and final harness builds (31s) passed.
Thirteen core cases inspect original TS files; seven JVM queue cases use synthetic
compressed batches and real pins. Three real-stager Android cases compile only.
No device/provider operation, upgrade or control enablement. Exact final/superseded
hashes/outcomes are in IPTV-SAMPLE-LOAD-DESIGN.md and its validation report.
Next: governed async refresh/ownership plus actual dynamic source/epoch-period
binding and admitted player/renderers with measured budgets and device offsets /
preroll/seek acknowledgement. Durable storage/providers/UX remain unfinished.
Temporary overnight automation remains active; old jobs stay paused. Checkpoint /
morning summary and explicit pause are due by 08:00 Brisbane.

## Asynchronous pinned reader — historical validated checkpoint

From 6842df1, added the actual owned reader consumer for exact committed seeks,
IO staging, stale/cancellation fences and retained pins/handles through worker and
cleanup failures/timeouts. Final full app compile (545s), 191 core tests (1.984s),
265 IPTV JVM tests (32 suites; zero failures/errors/skips; 7.011s test / 391s build)
and final harness builds (33s) pass. Twelve new JVM fixtures include actual
SharedCaptureRuntime ownership: failed reader close retains memory/pins while
another recording consumer stays active. Samples, recorder and logical budgets in
this fixture are synthetic, not decoder or durable-recording evidence.
Two actual-stager Android reader cases compile only; no device/provider operations.
Read IPTV-PINNED-READER-DESIGN.md and its report for exact hashes and scope. Dynamic
source/epoch loading, actual decoder/player confirmation and measured admission
remain required; controls disabled. Existing older automations remain paused;
temporary overnight continuation must checkpoint/summarize/pause by 08:00 Brisbane.

## Finite pinned period — historical validated checkpoint

From ae0c08b, implemented actual Media3 finite-period/sample-stream APIs over one
verified, pinned staged segment, frame seeks with full encoded preroll, read flags
and explicit retryable input closure. Final app compile (503s), 191 core tests
(1.868s), 253 IPTV JVM tests (31 suites, zero failures/errors/skips, 6.487s test /
370s build) and final current-source Android harness builds (36s) passed.
Read IPTV-PINNED-PERIOD-DESIGN.md and the matching validation report for exact hashes
and scope. The headless target manifest has no permissions or activities.
AM9 was observed Asleep through a targeted read-only query; no installs or
power/settings/provider operations. Two real Android period fixtures and earlier
staging/probe fixtures remain unexecuted. JVM sample payloads are synthetic.
Actual renderer/codec, dynamic loader/source and owned player gates remain
unvalidated and controls disabled. Next: live waiting/loading/epoch policy and
confirmed player/decoder/pin closure under measured aggregate admission.

## Physical storage checkpoint — historical

On codex/iptv from 4606418, added guarded physical space/unit/volume observations,
explicit caller margins and allocation/index overhead to capture admission and
streaming/index publication. Governed runtime sharing rejects unguarded stores;
storage failures stop without retry while preserving existing consumers and last-
good media. Final full app compile, 191 core tests, 244 IPTV JVM tests (30 suites,
zero failures/errors/skips) and final Android harness builds pass. The JVM suite
also exercised a real host FileStore probe in a small task temporary directory.
Exact readings, durations/hashes and contract are in IPTV-STORAGE-FENCE-DESIGN.md
and IPTV-STORAGE-FENCE-VALIDATION-20261006.json.

No device or provider operation occurred. Two new Android statvfs fixtures and
the previous staging fixtures remain unexecuted after the earlier Asleep observation.
OS quota/preallocation, validated device margins, USB/SMB and power-loss durability
remain unestablished. MediaSource/MediaPeriod loading/preroll/governed production
player integration are still required; controls remain disabled. Next work is
sample streams/loading/seek acknowledgements, measured aggregate memory/decoder
and device gates, then durable recording and remaining handoff scope. Temporary
overnight heartbeat remains active to its 08:00 Brisbane cutoff; older jobs paused.

## Overnight sample-staging checkpoint — historical

Continued on the authoritative sibling codex/iptv from 25395a2. Added bounded,
transactional compressed-sample staging tied to the pinned inspection proof,
shared video/audio PTS validation/normalization, and actual Media3 Window/Period
metadata with stable epoch identity and retained offsets. Final full app compile,
178 core tests, 230 IPTV JVM tests (28 suites, no failures/errors/skips) and final
isolated Android harness builds pass. Exact outcomes/hashes are recorded in
IPTV-SAMPLE-STAGING-VALIDATION-20261006.json and its companion design.

AM9 was observed Asleep after bounded reconnect/read-only power query. No install,
wake, power/CEC/settings or provider operation occurred. Six new staging Android
tests remain unexecuted; host timeline cases use synthetic completed metadata,
not Android extraction. No MediaSource/MediaPeriod loader or player commands are
connected; pause/timeshift/recording controls remain disabled. Next gates are
executed staging/codec validation, loading/preroll/seek acknowledgement, governed
admission/cleanup and physical storage margins. The new temporary overnight
heartbeat remains active until its 08:00 Brisbane cutoff; both old jobs stay paused.
The sections below retain historical milestone observations and test counts.

## Fresh-session checkpoint — historical

Continuation after the fresh-session handover: the clean sibling now has shared producer/store ownership, sequential bounded segment ingestion and a finite pinned local snapshot reader. They remain internal components, not enabled timeshift/recording. Full app compilation and 143 JVM tests passed; the final Android backend rerun passed 164 tests; see the latest section below, `IPTV-SHARED-CAPTURE-DESIGN.md` and `IPTV-SHARED-CAPTURE-VALIDATION-20261005.json`. The preceding checkpoint's separate-run limitations remain historical evidence.

Read [the complete continuation handover](IPTV-NEXT-SESSION-HANDOFF-20261005.md) first. The user requested a fresh chat, continued implementation and updated comprehensive draft release notes. Authoritative source is the sibling `E:/Codex/NuvioTV/work/NuvioTV-IPTV`, branch `codex/iptv`. This continuation builds on the handover commit `27f78ea`; inspect current Git HEAD/status before editing. The original dirty Fork remains preserved.

Latest delivered code adds shared capture ownership, bounded sequential ingestion and a finite local byte reader, validated by full app compilation, 143 JVM tests and 164 Android backend tests. Earlier code: `0211656` adds IPTV HUD, local-document XMLTV imports, bounded secure guide redirects and native tracks; `8f1316e` adds the bounded capture store. Playable timeshift/recording, Stalker and multiview remain unfinished. The earlier 112/133 HUD and separate seven-test capture runs remain historical evidence; the new full reruns supersede their separate-run limitation. The installed prototype is still the HUD build.

**Current cleanup state:** prototype force-stopped; validation packages removed; all fixture servers stopped; this run's reverse18767 and own UI dump removed; pre-existing reverse8765 preserved. AM9 remains asleep with last sleep reason HDMI. HUD/track/document UI checks await an active TV/AVR; no device power/CEC/security settings changed. The temporary away-time heartbeat remains PAUSED. The sections below are milestone history; test counts and device observations apply to their stated checkpoints.

## Workspace and scope

- Dedicated clean sibling clone: `NuvioTV-IPTV`, branch `codex/iptv`.
- Base: published `1.1.0-beta-nt4.1`, commit `574a2d41257ccc667828f54ba0c6ef40d9784984`, build 1458.
- Original dirty `NuvioTV-Fork` checkout is preserved. Fetch there encountered a broken Codex checkpoint ref; no repair/reset/stash/commit was attempted. Independent shallow clone succeeded.
- Research handover/designs are in the original checkout's `validation/iptv-research`. Do not repeat completed research or resume its paused automation.
- User authorised implementation while away and AM9 use, including NoBuffr installation. Latest clarification: comparator code may contain bugs. Original implementation and independent validation are required.
- Nuvio's Original/V2 themes, fonts, appearance settings, navigation, focus and supported locales remain the user-facing design. Comparator behaviour is evidence, not a design system or guarantee.

## Device evidence

- TiviMate reports **5.4.0 Beta 2**, versionCode 1000005402. Earlier 5.3.3 settings observations are historical; recheck before comparisons. Claimed AFR fixes are not independently established.
- Rechecked 5.4.0 playback preferences: Small buffer, hardware audio/video, passthrough and default surround on, tunneled playback off; AFR TV/VOD initially off. Temporarily enabled TV AFR only. BBC News reported FHD/50 FPS/stereo. AM9 stayed 3840x2160/59.94006 Hz during initial preview, switched to 3840x2160/50 Hz on fullscreen, and retained 50 Hz on return to the playing guide preview. Restored TV AFR off (VOD remained off); Home returned to 3840x2160/59.94006 Hz. TiviMate then stopped. This confirms this switching path only, not the claimed fixes or all rates/sinks. Unlike the NoBuffr UHD capture, this FHD channel was visible in screenshots; do not generalize black UHD screenshots into a playback failure.
- NoBuffr `com.nobuffr.app` 1.0.0/210271 installed successfully. APK signature verified, SHA-256 `3995631d8a4d23a83c2533b805d5032d09f700d52535f4688d69014beaaa0a69`.
- User signed into NoBuffr during this session. Its catalogue shows 13,740 channels. One UHD sports channel reports 3840x2160, 50 FPS and 5.1; these are app-reported values, not HDR/Atmos or decoded-output verification. Local timeshift is enabled with a 1 GB buffer. Paused UI showed 39 seconds behind live; resume retention remains inconclusive because an Up press after controls disappeared changed the channel. No recording/multiview or capacity result. The app was stopped after testing; account/preferences are preserved.
- Initial AM9 evidence is in the original checkout's `validation/iptv-research/evidence/am9-reference-20261005.json`; private screenshots under `captures/iptv-am9-reference-20261005`.
- Physical HDMI/audio route and representative channels remain unknown. No HDR/Atmos, provider quota, throughput or decoder-capacity claim.

## Foreground playback milestone (historical checkpoint)

Earlier milestones: ingest/admission `196dcf4`, source/catalogue persistence and M3U HTTP `9122fc6`, transactional XMLTV `b8e60e9`, bounded guide HTTP/cache `84924e3`, bounded browsing/mapping `53bd036`, native source setup/profile cleanup `7283bf5`. This continuation connects admission to a real foreground Media3 player and adds a first Nuvio live screen.

**Live TV** is reachable from the source screen. The new `player/iptv` route has a video preview, 24-row channel pages, source switching, favourite toggling/filtering, focused-channel programme information and an expanded video view. Focusing a row reads guide information; activating it requests playback. The screen uses NuvioTheme and TV controls. The existing playback-visible route rules also apply to this route for UI scale/quality handling. Home/sidebar/Search integration, a full time-grid guide and complete appearance/locale certification remain.

`LivePlaybackRuntime` now serializes real player ownership. It holds account, decoder and memory reservations throughout closure and will not construct another player until the old handle confirms both decoder and request closure. Cancellation closes the previous player without admitting a replacement; stale screen owners cannot stop the new owner. Unknown account limits remain one acquisition. The current envelope is one foreground IPTV decoder with 16 MiB acquisition plus 96 MiB viewer estimates under a 192 MiB budget. These are conservative policy estimates, not measured native/hardware limits or global accounting across VOD/trailers/external players. Sharing with a capture consumer is deliberately denied until a shared transport exists.

The Media3 adapter receives explicit `LIVE_CHANNEL` purpose and bypasses the VOD probe/cache/prefetch/thumbnail helpers. Its loader factory fences late requests, counts in-progress connects, cancels its own HTTP calls at closure and waits for all tracked requests to close. A failed/uncertain release keeps reservations held. Inspection of Nuvio's bundled Media3 AAR confirmed that a release timeout is delivered synchronously through the player error listener; the adapter treats it as failure and avoids releasing reentrantly from listener dispatch. No competitor APK code was inspected or copied.

Live entry points expose unknown length to extractors even when the server provides Content-Length, and reject nonzero entry-point offsets. Distinct HLS segment URIs can still use byte ranges. Automatic load retries are explicitly disabled through the retry-delay policy; setting only the minimum retry count to zero was insufficient in the first local failure fixture. Media redirects are conservatively rejected. Foreground loss, navigation disposal, profile changes and player errors trigger cleanup; returning to foreground does not automatically reopen media. No AFR, pause/timeshift, catch-up, recording or multiview controls are claimed.

**All 120 AM9 backend fixtures passed in 29.025 seconds, and all 93 JVM IPTV tests passed.** The nine new tests exercise blocked closure, cancellation during replacement/preparation, startup failure, account capacity, rejected sharing, stale screen cleanup and request-fence idempotence. Suites overlap. Full-app compilation and the prototype build passed. See `IPTV-LIVE-VALIDATION-20261005.json` for the actual video/UI observations, HTTP request trace, APK/source hashes and limitations. Device video uses a generated 640x360/25 FPS H.264 transport stream with silent AAC; there is no real-provider, audible-output, HDR or decoder-capacity result.

AM9 remote checks passed for explicit playback, channel replacement, expanded video/Back, background closure, favourite toggling/filtering and cold-restart persistence. Paging 33 channels as 24 plus 9 works even when the old first row is scrolled out of composition, with first-row focus restored. A final APK favourite playback also rendered successfully. During replacement the local server briefly retained two writer handlers, but peer inspection at new-open time showed only the new live connection; real-provider quota cleanup latency remains unverified. The prototype is installed and stopped. Temporary test packages, this run's ADB reverse and UI dump were removed, and the controlled server was stopped. User apps/accounts were untouched.

Source/profile cleanup from the previous milestone remains in place. Source forms now clear the previous status when opening a new form. Programme times are shown only for precise timestamps. English resource fallback remains; no complete translation claim. Sources still share the conservative default account alias until explicit account-group UI exists.

## Xtream source milestone

Foreground live playback is saved in `7ec290a`. This continuation adds the Xtream source form (server base, username, password) and metadata refresh using authentication, live categories and live channels. The common endpoint/URL pattern follows the panel vendor's [Xtream API documentation](https://xui.live/docs/api/xtream/); this is a compatible adapter, not a universal specification or provider interoperability result. M3U and Xtream sources coexist with independent identities and the existing manually linked XMLTV feeds. The form uses NuvioDialog, masked credential fields and scrollable content while retaining Save/Cancel.

The dedicated adapter makes three sequential metadata requests. It never fetches logos, `direct_source`, server-advertised hosts or media during refresh. URL query/path components are encoded separately; a server base with optional directory is accepted, while query-bearing API URLs, userinfo, fragments and invalid credentials are rejected. Transport has no redirect following or automatic retry. Expanded response budgets are 256 KiB for authentication, 1 MiB for categories and 8 MiB for live rows. Strict UTF-8, JSON grammar, duplicate-key, 16-level nesting and 64 KiB string-token checks run before the platform JSON parser. Catalogue limits and transactional last-good/shrink-review rules remain in force.

Independent device testing caught a TV focus trap in the credential field; source forms now handle remote Up/Down and IME Next/Done. Testing also caught a stale pooled connection failure across sequential HTTP/1.0 metadata responses. It reproduced on both AM9 and JVM; explicitly requesting connection closure resolved it without allowing automatic retries. Xtream, M3U and XMLTV metadata requests now use that policy, with transport regression cases for all three. This costs connection reuse during metadata refresh, while avoiding speculative retry behaviour. Safe diagnostics contain only stage, exception type, failure enum and code location, never URLs, credentials, response text or exception messages.

Account authentication must be active; a known elapsed expiry stops the refresh. Advertised connection limits never raise the existing conservative one-stream policy. The adapter prefers an advertised TS output, accepts explicitly advertised HLS when TS is absent, and defaults to TS when the format list is missing. There are no speculative fallback opens. Archive availability/days are retained only as advertised metadata. Stable provider IDs preserve local channel identity and favourites across credential changes; an edit still invalidates old playback eligibility until a fresh catalogue commits. Authentication, partial-data and stale-ticket failures do not replace last-good rows.

**107 JVM tests and 125 AM9 backend fixtures passed** (the suites overlap). New coverage includes 12 Xtream transport tests, 2 M3U/XMLTV HTTP/1.0 regressions and 5 Android repository tests. The device harness has no network permission; the production adapter runs against intercepted responses with real Android JSON, Keystore and SQLite. UI/playback evidence and cleanup are in `IPTV-XTREAM-VALIDATION-20261005.json`. Source hashes, APK hash and the localhost request trace are retained there. No user provider account or comparator app was used.

AM9 checks covered the Original add form, V2 editing, source coexistence, denied authentication, partial-data retention and explicit playback of both synthetic Xtream channels. The final APK passed the originally failing HTTP/1.0 fixture without a response Connection header. The final cold restart preserved the source and favourite; source/filter navigation opened no media. The existing guide fixture expired during this run, so current programme display is not newly certified. The prototype is installed and stopped, temporary validation packages and this run's reverse/UI dump are removed, and the fixture server is stopped. Broader playback and appearance limitations remain as listed below.

## HLS transport milestone

Xtream setup is committed in `e636438`. Controlled AM9 HLS testing now covers separate TS segments, a sliding six-segment live window, byte-range segments, channel replacement and Home cleanup. The initial player rendered the ordinary and byte-range streams, but failed an extensionless playlist because its media type was inferred from the URL.

A native Nuvio stream-format dialog now offers Auto, HLS and MPEG-TS for the focused channel. The choice is a profile/source-scoped channel overlay, preserved through refresh and process restart alongside favourites and manual guide mapping. Schema 3 migrates both earlier catalogue schemas without changing identities or credentials. Format changes invalidate browse cursors, are revalidated before player creation, and are included in the acquisition variant key. Saving a choice performs no media request; it applies on the next explicit channel activation. Choosing HLS made the extensionless fixture render successfully. This is an explicit compatibility setting, not automatic sniffing or a probe/fallback pipeline.

The two-rendition failure fixture exposed a second bug: Media3's default fallback policy opened the alternate rendition after a 503 segment error even though retry delays were disabled. The live adapter now also rejects fallback selections. The final AM9 run requested the master, the primary playlist (twice during initial preparation) and one failing primary segment, then displayed an error. It did not request the alternative playlist/segment or retry in the background. The earlier failing trace is retained for comparison. Normal HLS playlist refresh is distinct from retrying a failed segment.

**107 JVM tests and 128 AM9 backend tests passed** (overlapping suites); the full compile and final prototype assembly passed. Three new Android cases check durable format choices, independent source/profile overlays and stale paging, and migration from schema 2; the existing schema-1 migration also passes. The harness has no network permission. Final APK UI/device observations, hashes, request trace and cleanup are saved in `IPTV-HLS-VALIDATION-20261005.json`. Reproduction tools are `tools/iptv-device-tests/hls_fixture.py` and `HLS-FIXTURES.md`.

The final prototype is installed and stopped. Only synthetic sources were used. Temporary test packages, this run's reverse and UI dump were removed, and the localhost fixture server was stopped. Existing device apps, accounts, preferences and recordings remain preserved. No recording/timeshift/AFR or broad provider/codec/HLS-format certification is implied.

## Next concrete implementation work

1. Complete the live player integration: native Nuvio transport/track/audio preferences, richer state/error handling, AFR/display handoff, decoder capability accounting and one aggregate owner across VOD/trailers/IPTV. Extend HLS coverage to fMP4, separate audio, encryption/DRM and adaptive switching, plus redirects and provider-specific transport behaviour before real-account trials. Generated progressive TS, HLS TS and HLS byte-range paths are now device-tested.
2. Build shared transport/capture ownership, seekable local buffering and durable internal/USB/SMB recording/scheduling. Keep reservations until the final real consumer closes; runtime currently rejects sharing. Make existing exit/kill paths capture-aware before background recording is enabled.
3. Finish Xtream API EPG/automatic XMLTV setup and provider compatibility; authentication/category/live refresh is now connected. Finish explicit shared account groups, source/feed deletion, priority/manual mapping, category facets, feed paging beyond 200 and durable rejected-candidate review.
4. Extend the first live screen with a proper time-grid guide, Home favourites/sports/recordings, Search and persistent focus anchors. Current pages requery every 30 seconds while foreground and restart on revision mismatch; event-driven invalidation remains. Never render from the whole-source catalogue snapshot. Recheck source-form remote movement with IME visibility recorded: raw Down did not advance the active text field in this HLS setup run; Tab worked. The prior password-to-Save fix is not a certification of every field/IME state.
5. Complete provider catch-up, governed multiview, all supported locales and appearance/scale validation. Full-screen currently expands the video while retaining the simple toolbar; it is not the finished immersive player UX. Retain independent channel identities even when names match.


## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- HLS milestone full compile, prototype build and JVM suite: `validation/iptv-core/hls-verified-build.log` (107 IPTV tests); device run: `hls-am9.txt` (128 tests, zero failures, 36.495 seconds). Durable evidence: `validation/IPTV-HLS-VALIDATION-20261005.json`. Earlier Xtream/live/setup evidence remains in its JSON files.
- Device harness instructions: `tools/iptv-device-tests/README.md`. It builds actual production sources through Gradle Sync tasks and runs separately from the Nuvio app. Check the JUnit summary because adb can exit zero on a failed test run. Fixtures cover close/reopen, not abrupt process death or physical power loss; no recording/playback/performance claims.
- Full compile: `gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2`. Existing JVM suite: `:app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.*`. Redirect verbose baseline warnings to a log.
- Clean sibling checkout is also available at `E:/Codex/NuvioTV/work/NuvioTV-IPTV`; use its branch `codex/iptv`. The active chat's sandbox still covers the original Fork checkout, so sibling writes required escalation.
- This continuation staged only its own files under original `captures/iptv-browse/files`, copied using `captures/iptv-browse/sync.py`. Older staging is under `captures/iptv-guide-http`, `captures/iptv-guide`, `captures/iptv-storage` and `captures/iptv-development`. Do not resync old staging over newer sibling changes.
- No AGENTS.md found in either checkout or applicable parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` is PAUSED following the user return. The user subsequently authorised continued work in chat; the old away-time schedule was not resumed. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.

- Source setup staging: original `captures/iptv-setup/files`, copied with `captures/iptv-setup/sync.py`. Never resync older staging over later sibling edits. Private prototype screenshots and UI fixture helper are in `captures/iptv-setup`.

- Live runtime staging: original `captures/iptv-live/files`, copied with `captures/iptv-live/sync.py`. Do not resync old staging over later changes. Controlled media/server/UI evidence is in `captures/iptv-live`.

- Xtream staging: original `captures/iptv-xtream/files`, copied with `captures/iptv-xtream/sync.py`. Controlled server/UI evidence is in `captures/iptv-xtream`. Never resync older staging over later changes.

- HLS staging: original `captures/iptv-hls/files`, copied with `captures/iptv-hls/sync.py`. Private generated media, screenshots and request trace are in `captures/iptv-hls`. Do not resync older staging over later changes.

## User return and expanded scope (5 October)

The user returned and explicitly authorised continued implementation. The temporary away-time heartbeat is PAUSED; do not resume it. Maintain `IPTV-RELEASE-NOTES-DRAFT.md` as a comprehensive unreleased draft, distinguishing implemented/validated behaviour from planned support.

Additional requirements: IPTV adaptation of the existing ExoPlayer HUD with live metrics; local XMLTV .xml files and URLs (including shortened links); Stalker Portal alongside M3U and Xtream Codes API; subtitles/CC/DVB subtitles/teletext; timeshift; multiview with a different source/playlist per pane. XMLTV is the guide format. No new work is certified merely because a comparator implements it.

At that checkpoint HUD and guide-file/redirect implementation was in progress; these are now committed in `0211656`, with screen validation still pending. Larger milestones remain. A public HEAD request to the exact supplied https://tinyurl.com/epg-ss11 returned HTTP 404 at 06:29 UTC; no guide data was obtained.

## HUD, guide files, track controls and upstream review continuation

Implemented an IPTV sampler using the existing fork HUD panel, with per-session body-byte/rate accounting, prepare-to-first-frame time, rebuffer counts/duration, manifest live offset or unavailable, buffer depth, formats and renderer dropped buffers. All player reads remain on the main thread; transfer counters are synchronized. Added a native track dialog for exposed audio/text groups, Automatic/Off and supported selections; track selection resets with the player. No CC/DVB/teletext rendering certification yet.

Guide setup now offers Android OpenDocument. Content URIs are encrypted with other feed endpoints; persistent read permission is requested at Save and the existing bounded importer reopens the document on Refresh. Missing/revoked permission and malformed replacement retain last-good rows. HTTP guide short links can redirect to HTTPS cross-origin targets, with existing count/byte/cancellation limits. Redirected imports clear cache validators; redirected requests do not forward validators, cookies, auth or referrer. Non-same-origin HTTP and HTTPS downgrades remain rejected. The user's exact TinyURL returned 404 on HEAD and GET; no XMLTV retrieved.

Full application compile, distinct prototype assembly and 112 JVM tests passed (`iptv-core/hud-guides-build.log`, 8m49s). 133 real Android backend fixtures passed in 29.168s (`hud-guides-am9.txt`); test app has no network permission. New cases cover telemetry startup/stalls/rates, secure redirects/validator isolation, local-document refresh/closure and last-good retention after parse/permission failures. Suites overlap; no broad provider claim.

Prototype installed on AM9. Initial Live TV UI rendered, but screen tests encountered HDMI sleep 15 seconds after wake; `dumpsys power` reported `mLastSleepReason=hdmi`. An asynchronous question asks the user to leave the TV/AVR on or defer screen tests. No CEC/security/power preferences changed. Backend and independent work continue. Do not trust `next-source.xml` from this attempt: uiautomator failed but the old helper read a stale dump. Helper now removes its own prior dump and requires a successful fresh dump. No new playback/track/HUD visual success is claimed yet.

AM9 has no native OPEN_DOCUMENT or GET_CONTENT picker. A test-only picker/provider was added to the instrumentation APK to allow a real URI-grant/cold-restart import check with one synthetic XMLTV document and no personal files. It is NOT a shipping file picker and does not resolve the AM9 product limitation. Compilation initially failed on heterogeneous array type inference; explicit `arrayOf<Any>` fixed it. The updated test APK was installed temporarily and both validation packages were subsequently removed at cleanup. No backend test rerun needed for this test-only UI addition.

User additionally requested review of upstream PR https://github.com/NuvioMedia/NuvioTV/pull/3788. Attached to this chat. Focused review is saved in `IPTV-UPSTREAM-PR3788-REVIEW.md`; pinned head e10c639200d5821d1cbc71bdd438ccbd6b1ff7ff. Exact policy was compiled in an isolated scratch JVM harness with unrelated settings stubs. Reproductions show backward-then-forward preview selects 60s instead of 30s, and measured 20s offset displays LIVE; RAM load target is also conflated with retainable window in recovery. No PR changes were imported, no comments/reviews posted, no PR APK installed. Useful requirements are retained for our actual capture/timeshift model.

Active scratch: original `captures/iptv-hud` and `captures/iptv-guides-local`; source already synced. Do not rerun prepare scripts over newer code. The temporary HLS/WebVTT server used loopback 18767 with `captures/iptv-hud/fixture.py --directory captures/iptv-hud --subtitles`; both the server and reverse18767 were stopped/removed at cleanup. Preserve the old reverse8765. Generated test media copied from earlier fixture. Current helper screenshots/dumps remain private. Release notes are in `IPTV-RELEASE-NOTES-DRAFT.md` and must be kept current.

Cleanup checkpoint: prototype stopped; temporary validation packages and reverse 18767 removed; pre-existing reverse 8765 preserved; HLS/WebVTT fixture server stopped; own UI dump removed. Device remains asleep as initially observed. Synthetic picker was compiled/installed but not exercised. Durable evidence: `IPTV-HUD-GUIDES-VALIDATION-20261005.json`. Next independent work is actual capture retention/seek bounds; outstanding screen tests remain queued for an active HDMI display.

## Capture spool foundation

HUD/guide/track milestone committed as `0211656`. The next independent component is `CaptureSegmentStore`, a private exclusively locked spool of completed transport-adapter segments. Segment and index writes are synced and atomically promoted. Recovery validates bounded metadata, ordering and file lengths, and cleans only recognized unreferenced files in an owned directory. It refuses to claim a nonempty unmarked directory. Logical retained bytes and segment count are bounded; one staged segment plus index/filesystem overhead require additional reserved disk. Actual allocated disk blocks and free-space margins must still be accounted for by the Android storage/admission layer.

Pause/recording anchors protect a segment and successors. Readers hold their own pins until close; releasing a separate anchor cannot evict an open reader. A full protected spool reports backpressure instead of dropping the paused position. The store refuses closure while consumers remain. Cancellation, oversized input and read failures before promotion do not replace committed rows. Old files are retired only after index promotion; a cleanup failure blocks allocation on the next append. Latest contiguous bounds exclude older gaps/discontinuities and use an explicit exclusive end; transport adapters still have to prove decoder/keyframe-safe entry points.

Seven focused filesystem tests pass on both standalone JVM (0.223s) and AM9 (0.39s). The Android harness builds actual production sources (18s). Evidence: `IPTV-CAPTURE-STORE-VALIDATION-20261005.json`; logs under `iptv-core/capture-*`. The earlier 112-test app JVM run and 133-test AM9 run were not repeated for this isolated new class; these seven tests are additional focused runs, not a claimed combined whole-app run.

This is an internal storage foundation, NOT playable timeshift/recording. No media capture adapter, player reader, foreground capture service, schedule, USB/SMB delivery, process-death lease recovery or physical-power-loss guarantee is implemented by this class. Reopen tests are normal close/reopen, with synthetic orphan/truncation conditions. Current prototype remains the `0211656` HUD/guide build; this component has not been rebuilt into its APK. Next: wire a bounded transport adapter and independent capture/viewer ownership, establish real decodable seek bounds, then native pause/record controls and durable recording/schedule leases. Preserve the upstream PR review's timing/seek findings.

Temporary Android fixture packages removed after the focused tests. AM9 still reports asleep with last sleep reason HDMI. No playback/provider traffic, system preferences or personal files changed. Staging: original `captures/iptv-capture`; do not resync old milestones over newer source.

## Shared capture ownership and bounded ingestion continuation

Built on clean `27f78ea` in the authorized sibling; original Fork source remains untouched. `SharedCaptureRuntime` reserves producer memory and the whole spool separately from viewer/recording leases. Closing one consumer preserves other consumers and the producer. Decoder reservations remain held on failed consumer closure; account/capture memory/storage remain held until producer and store closure both succeed. Forgotten reader pins prevent store close. Final closure fences further joins; explicit cleanup retries and shutdown handle uncertain/cancelled joins. Stale tokens cannot stop replacement acquisitions, and admission sharing without an owned pipeline is rejected.

`SegmentCaptureTransport` copies one segment body at a time through bounded append, closing it before another pull. Backpressure and failures stop ingestion without automatic retry. Cancellation rejects publication of late bodies. Source and body closure must confirm before capacity is released. The source interface requires actual protocol adapters to fence/cancel late requests; this milestone does not implement network/HLS/TS parsing or independent decode verification.

`openSnapshotFrom` atomically pins an uninterrupted fixed range. `CaptureSnapshotReader` reads across its files, stops at a gap/discontinuity, excludes future appends and holds retention until explicit close even after EOF. It is a local byte-reader building block, not a Media3 live-tail/seek integration. Store append still serializes operations during input copy; concurrent playback latency requires work.

Twenty-four new tests plus the seven existing store tests passed in the focused JVM run (31 tests, 1.112s). Full app compilation and 143 JVM tests passed; the final Android backend rerun passed 164 tests in 30.233s; its final harness compiled production sources in 47s and has no network permission. Full-app/JVM result and source hashes are in `IPTV-SHARED-CAPTURE-VALIDATION-20261005.json`; detailed ownership contracts and next implementation boundaries are in `IPTV-SHARED-CAPTURE-DESIGN.md`.

AM9 remained asleep, last sleep reason HDMI. Temporary test packages removed; pre-existing reverse8765 preserved. No prototype reinstall or provider traffic. Installed prototype/UI-validation limitations and all larger scope remain unchanged. Keep both automations paused. Next: implement a bounded protocol adapter with independently verified decoder-safe entry points, then a waiting/ended/expired local-reader timeline and production capture/player integration. Durable leases/schedules/service/storage and the rest of the handover remain unfinished.

## HLS capture continuation — bounded protocol ingestion

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

## Fresh-session documentation checkpoint

User requested a fresh continuation session, complete handoff and updated draft
release notes. Reconciled `IPTV-NEXT-SESSION-HANDOFF-20261005.md` into one current
entry point covering implementation eaa43e5, request/body ownership findings,
bounded HLS limitations, overlapping 176 JVM/190 Android results, successful
memory flags, device cleanup, earlier PR/comparator findings and ordered remaining
work. Draft notes now include a current summary, latest commit history and fixes,
and remove obsolete present-tense no-adapter/asleep statements. Component upgrades
remain separate. This checkpoint changes documentation only; previous code/test
evidence remains applicable. Continue on codex/iptv in the authoritative sibling.

## Controlled TS entry and local-reader continuation, 5 October 2026

Continued from clean 3ae8882 on the existing IPTV branch. Added bounded TS header
inspection, independent FFprobe/strict FFmpeg and AM9 codec checks, a hash-bound
local Media3 extraction bridge, explicit live-reader states and store source-I/O
concurrency correction. Reproduced and scoped a real missing-last-sample issue in
platform and shipped Media3 extraction. No bundled binary or version was changed.
150 core tests and 214 Android tests passed; complete capture/local-read integration
decoded 150 video/283 audio frames. See IPTV-TS-ENTRY-VALIDATION-20261005.json for
current full build/JVM outcomes and exact hashes. Draft notes/handoff are updated.
Temporary packages removed, device Awake without task wake, prototype unchanged,
no provider traffic. Final reverse list empty; this task made no reverse changes.
Next gates remain inspected retained-sample timeline, production player sharing,
physical allocation and pause/recording/durable services plus the broader handoff.

Final resumed validation: app Kotlin compile PASS (558s), full IPTV JVM
suite PASS (196 tests in 23 suites, zero failures/errors/skips), annotated
Android harness build PASS (75s). The first full JVM run was stopped
at the user pause; the successful resumed run is separate evidence. Device checks
were not repeated for the two annotation-only edits; tested APK hashes and final
compiled APK hashes are distinguished in the report. No device wake/install was
performed during this resumed verification. Temporary overnight continuation is
authorized; leave the older automations paused and stop the new one by 08:00
Brisbane on 6 October. Next implementation gates remain as described above.

## Retained inspection and sample/seek policy, 5 October 2026

Added committed-row/index ownership, bounded cache and pinned inputs that recheck
length/hash through verified EOF, including skip; pins require explicit close.
Added real-PTS/audio epochs with stable positions across wrap/eviction and explicit
gap/configuration/timestamp boundaries. Pending seeks use the same anchor in both
directions, retain it until exact acknowledgement, reject expiry/stale commits,
and explicitly select the newest captured tail. 171 core tests passed (21 new).
Full app/JVM validation is running. The initial failed case exposed an overbroad
fixture SPS mutation matching a TS packet boundary; corrected the fixture helper.
Production Media3/playback integration and all remaining storage/recording gates
remain pending. See the retained-media design/evidence report for final results.

Final retained-media checks: app compile PASS (523s); full IPTV JVM PASS, 217 tests
in 26 suites, zero failures/errors/skips (443s build, 3.522s JUnit execution);
Android harness build PASS (70s). Source hashes match the exact final working tree.
No device/installation/provider work during this continuation. Next remains
transactional sample staging and actual Media3/player integration, as specified
in the current design/handoff; pause/timeshift/recording controls remain disabled.
