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

The ingest/admission foundation is saved in `196dcf4`; persistent sources/catalogues and the production M3U HTTP coordinator are in `9122fc6`. This continuation adds profile-scoped XMLTV feed persistence, encrypted feed endpoints/cache validators, gzip sniffing with separate compressed/expanded byte limits, bounded staging batches and indexed, paged programme queries. Complete valid imports atomically activate their guide and validators. Cancellation, malformed/partially rejected XML, stale tickets, conflicting channel definitions, orphan programmes, empty feeds, suspicious shrink and capacity failures preserve the previous guide. Endpoint edits invalidate stale imports/visible guide/cache; label-only edits preserve them. Shrink comparison applies only to the same endpoint configuration.

**Final full Android Kotlin compile passed. 90 fixture tests passed on the AM9** (64 core + 13 catalogue storage + 11 guide storage + 2 HTTP/repository integration cases), in 12.627 seconds. **73 JVM IPTV regressions passed** (64 core + 9 metadata HTTP cases). These suites overlap; do not add their totals as unique tests. The final Android fixture and test APKs have no INTERNET permission. No provider streams or user credentials were used, and both disposable validation APKs were removed. No user-facing Nuvio APK is installed.

Guide tests cover close/reopen with language/timestamp preservation, same IDs in different feeds/profiles, half-open windows, overlapping programmes, unknown end times, deduplication, cancellation after a flushed batch, a newer refresh from a second database handle, and a real SQLITE_FULL failure with last-good preservation/retry. The initial device run had two ineffective fixture triggers (their byte thresholds exceeded their input size); corrected fixtures calculate the boundary after 110 records and the final 90-test run passed. Review additionally fixed an endpoint-change shrink-baseline defect. Earlier catalogue storage validation caught Android writer-page-limit and rollback-cleanup defects; both stores retain those corrections.

This remains backend foundation. Guide import accepts caller-owned streams; **the production guide HTTP coordinator, persisted source/feed associations and guide UI are still absent**. There is no user-facing Live TV route, Xtream HTTP adapter or real live/capture coordinator yet. Existing player callers still default to VOD purpose. Account/device admission is bookkeeping until wired to actual upstream ownership.

## Next concrete implementation work

1. Add paged/indexed catalogue browsing and profile-scoped source management integration. `IptvCatalogueStore.snapshot` currently decrypts the whole source for reconciliation/tests: use worker dispatchers, and do not wire this directly to large guide renders. Measure realistic catalogue sizes before claiming performance. Source deletion/profile lifecycle and durable review of a specific suspicious candidate remain unimplemented. Keep account aliases explicit and shared for quota accounting.
2. Wire a production XMLTV HTTP coordinator to the new `IptvGuideStore`, with atomically captured ticket/endpoint/validators, stale 304 rejection, bounded response streaming, cancellation, redirect rules and redacted failures. `parseGuideInput` already handles plain/gzip streams and independent byte limits. Add persistent source/feed associations and manual mapping using the existing feed-scoped resolver. Feed listing/deletion, durable review of a specific rejected candidate, scheduled refresh and advancing retention windows remain. Imports retain only the requested time window (at most 31 days); stale active data is not automatically pruned. Both stores' 256 MiB defaults limit logical SQLite pages only; WAL/journal, total free space, native memory and capture storage need separate accounting. Test realistic EPG sizes before performance claims.
3. Extend transport for Xtream authentication/account/category/live metadata. Current Xtream support is a pure metadata parser; `IptvPlaylistRepository` explicitly reports unsupported source kinds. M3U HLS inputs are classified separately and never imported as channel rows. Invalid/partial/shrunken candidates retain the last working generation; HTTP validators activate only with a successful catalogue.
4. Add Nuvio-native source setup and watch-first Live TV guide in both appearance shells, using theme typography/colors/focus and localized strings. Integrate Home favourites/sports/recordings and Search without merging channels by name. Add the live route to navigation and account for live display ownership in the existing UI frame-rate governor.
5. Wire live session admission and player purpose before opening media, cancel VOD probes/thumbnails, and govern one audio/display owner. Build shared transport/capture, seekable local buffer and durable recording services next, preserving the full scope below. Admission is not a transport implementation and the existing app exit/kill path still needs capture-aware handling.

## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- Latest compile log: `validation/iptv-core/guide-app-final-compile.log`; 73-test JVM build: `guide-app-build.log`; final device run: `guide-am9-final.txt` (90 tests, zero failures, 12.627 seconds). Generated logs/APKs remain ignored. Durable result/source hashes: `validation/IPTV-GUIDE-VALIDATION-20261005.json`; earlier storage evidence remains in `IPTV-STORAGE-VALIDATION-20261005.json`.
- Device harness instructions: `tools/iptv-device-tests/README.md`. It builds actual production sources through Gradle Sync tasks and runs separately from the Nuvio app. Check the JUnit summary because adb can exit zero on a failed test run. Fixtures cover close/reopen, not abrupt process death or physical power loss; no recording/playback/performance claims.
- Full compile: `gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2`. Existing JVM suite: `:app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.*`. Redirect verbose baseline warnings to a log.
- Clean sibling checkout is also available at `E:/Codex/NuvioTV/work/NuvioTV-IPTV`; use its branch `codex/iptv`. The active chat's sandbox still covers the original Fork checkout, so sibling writes required escalation.
- This continuation staged only its own files under original `captures/iptv-guide/files`, copied using `captures/iptv-guide/sync.py`. Older staging remains under `captures/iptv-storage` and `captures/iptv-development`. Do not blindly resync old staging after editing sibling files: it can overwrite newer work.
- No AGENTS.md found in either checkout or applicable parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` continues hourly, at most ten runs, and must pause by 19:00 Brisbane on 5 October 2026 or when the user changes the plan. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.
