# IPTV implementation progress

Updated 5 October 2026, Australia/Brisbane.

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

## Current milestone

The ingest/admission foundation is in `196dcf4`, persistent sources/catalogues and M3U HTTP coordination in `9122fc6`, and transactional XMLTV guide storage in `b8e60e9`. This continuation connects the guide store to a production HTTP coordinator. It captures endpoint, validators and refresh ticket atomically, checks stale 200/304 responses, keeps wire bytes visible to a bounded gzip decoder, limits simultaneous guide requests to two, bounds same-origin redirects, rejects cross-origin/downgrade redirects and partial HTTP responses, and redacts network/parser failures. Cancellation closes the active call and waits for response/staging cleanup before the refresh finishes. SQLite capacity failures have a distinct result.

Conditional requests are allowed only when the active guide actually retained the entire requested date window. Extending or moving the window outside that coverage forces an unconditional download; a server 304 cannot silently leave missing days. Guide database v2 adds retained-window coverage. The v1 migration preserves existing rows, but its unknown coverage forces a full refresh.

**Full app Kotlin compilation and 84 JVM IPTV tests passed. 97 AM9 fixture tests passed** in 14.638 seconds (64 core, 13 catalogue storage, 11 guide storage, 2 playlist repository, 7 guide repository). JVM coverage includes 11 guide HTTP cases using localhost MockWebServer or controlled interceptors, plus 9 existing metadata HTTP cases and the 64 core cases. These suites overlap; do not add their totals as unique tests. Device fixture APKs have no INTERNET permission; integration responses are in-process. No provider requests or user credentials were used. Both validation APKs were removed after testing; no user-facing Nuvio APK was installed.

New regressions cover same/cross-origin redirects, conditional 304s, gzip files and HTTP-encoded gzip, chunked transfer limits, expansion limits, partial/unsupported responses, redacted network errors without automatic retry, redirect loops, two concurrent guide requests across client instances, socket cancellation, retained-window cache behaviour, stale responses during edits/refreshes, truncated gzip/invalid programme preservation, cancellation after staged rows, SQLite capacity reporting and the v1 migration. Earlier storage/parser validation remains applicable in the prior evidence files.

This is still backend foundation. **Persisted source/feed associations, Nuvio-native source setup/guide UI, Xtream HTTP and actual live/capture ownership are not implemented.** Existing player callers default to VOD purpose; account/device admission is still bookkeeping. No provider interoperability, playback/recording, abrupt process-death, full-scale performance or device-capacity claim.

## Next concrete implementation work

1. Add paged/indexed catalogue browsing and profile-scoped source management integration. `IptvCatalogueStore.snapshot` currently decrypts the whole source for reconciliation/tests: use worker dispatchers, and do not wire this directly to large guide renders. Measure realistic catalogue sizes before claiming performance. Source deletion/profile lifecycle and durable review of a specific suspicious candidate remain unimplemented. Keep account aliases explicit and shared for quota accounting.
2. Add persistent source/feed associations and manual mapping using the existing feed-scoped resolver. The production `IptvGuideRepository` now handles HTTP-to-store refresh; wire it to source management and scheduled refresh on worker dispatchers. Feed listing/deletion, durable review of rejected candidates, and automatic advancing retention/pruning remain. Imports retain a requested window of at most 31 days; v2 coverage prevents unsafe conditional refreshes beyond that window. Both stores' 256 MiB defaults bound logical SQLite pages only, not WAL/journal or total storage. Measure realistic EPG sizes before claiming performance.
3. Extend transport for Xtream authentication/account/category/live metadata. Current Xtream support is a pure metadata parser; `IptvPlaylistRepository` explicitly reports unsupported source kinds. M3U HLS inputs are classified separately and never imported as channel rows. Invalid/partial/shrunken candidates retain the last working generation; HTTP validators activate only with a successful catalogue.
4. Add Nuvio-native source setup and watch-first Live TV guide in both appearance shells, using theme typography/colors/focus and localized strings. Integrate Home favourites/sports/recordings and Search without merging channels by name. Add the live route to navigation and account for live display ownership in the existing UI frame-rate governor.
5. Wire live session admission and player purpose before opening media, cancel VOD probes/thumbnails, and govern one audio/display owner. Build shared transport/capture, seekable local buffer and durable recording services next, preserving the full scope below. Admission is not a transport implementation and the existing app exit/kill path still needs capture-aware handling.

## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- Latest full compile/JVM log: `validation/iptv-core/guide-http-app-final-build.log` (84 tests); device build: `guide-http-device-final-build.log`; final device run: `guide-http-am9-final.txt` (97 tests, zero failures, 14.638 seconds). Generated logs/APKs remain ignored. Durable result/source hashes: `validation/IPTV-GUIDE-HTTP-VALIDATION-20261005.json`; prior guide/storage evidence remains in its earlier JSON files.
- Device harness instructions: `tools/iptv-device-tests/README.md`. It builds actual production sources through Gradle Sync tasks and runs separately from the Nuvio app. Check the JUnit summary because adb can exit zero on a failed test run. Fixtures cover close/reopen, not abrupt process death or physical power loss; no recording/playback/performance claims.
- Full compile: `gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2`. Existing JVM suite: `:app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.*`. Redirect verbose baseline warnings to a log.
- Clean sibling checkout is also available at `E:/Codex/NuvioTV/work/NuvioTV-IPTV`; use its branch `codex/iptv`. The active chat's sandbox still covers the original Fork checkout, so sibling writes required escalation.
- This continuation staged only its own files under original `captures/iptv-guide-http/files`, copied using `captures/iptv-guide-http/sync.py`. Older staging remains under `captures/iptv-guide`, `captures/iptv-storage` and `captures/iptv-development`. Do not resync old staging over newer sibling changes.
- No AGENTS.md found in either checkout or applicable parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` continues hourly, at most ten runs, and must pause by 19:00 Brisbane on 5 October 2026 or when the user changes the plan. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.
