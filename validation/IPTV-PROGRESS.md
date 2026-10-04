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

The initial ingest/admission foundation is saved in commit `196dcf4`. This continuation adds persistent profile-scoped sources and catalogues, encrypted source credentials/channel payloads/cache validators, separate user overlays, transactional generations, stale refresh rejection and a production M3U HTTP-to-storage coordinator. Source renaming preserves playback/cache; connection/kind/account edits invalidate old imports and prevent playback using old credentials while keeping the previous catalogue visible.

**Final full Android Kotlin compile passed. 74 fixture tests passed on the AM9** (59 core + 13 storage + 2 HTTP/repository integration cases), using a separate `Nuvio IPTV Validation` app. The existing 68 JVM IPTV regressions also passed during this continuation. These suites overlap; do not add their totals as unique tests. The final Android fixture and test APKs have no INTERNET permission; HTTP integration tests use in-process fake responses. No provider streams or user credentials were used. The validation APKs were removed after testing; no user-facing Nuvio APK was installed.

Real device testing caught and fixed two storage defects: the SQLite page-count limit had to be set on the transaction-pinned writer connection, and Android rollback cleanup could otherwise mask the original SQLITE_FULL error after SQLite auto-rollback. The capacity fixture triggers the real error without filling device storage, verifies the previous catalogue/ETag survive, and retries successfully after restoring capacity. Other cases cover authenticated encryption, tamper/profile rejection, close/reopen, cancelled/failed row insertion, separate database handles, edited credentials during a response, tombstone/favourite restoration and source renaming.

This remains backend foundation. There is no user-facing Live TV route, persistent guide database, Xtream HTTP adapter or real live/capture coordinator yet. Existing player callers still default to VOD purpose. Account/device admission is bookkeeping until wired to actual upstream ownership.

## Next concrete implementation work

1. Add paged/indexed catalogue browsing and profile-scoped source management integration. `IptvCatalogueStore.snapshot` currently decrypts the whole source for reconciliation/tests: use worker dispatchers, and do not wire this directly to large guide renders. Measure realistic catalogue sizes before claiming performance. Source deletion/profile lifecycle and durable review of a specific suspicious candidate remain unimplemented. Keep account aliases explicit and shared for quota accounting.
2. Add transactional XMLTV feed storage, bounded gzip transport, time-window indexes/retention and guide association using the existing feed-scoped resolver. Never publish parser callbacks directly. Test cancellation, capacity failure, concurrent feed refresh and restart. The catalogue store's 256 MiB default limits logical SQLite pages only; WAL/journal, total free space, native memory and capture storage need separate accounting.
3. Extend transport for Xtream authentication/account/category/live metadata. Current Xtream support is a pure metadata parser; `IptvPlaylistRepository` explicitly reports unsupported source kinds. M3U HLS inputs are classified separately and never imported as channel rows. Invalid/partial/shrunken candidates retain the last working generation; HTTP validators activate only with a successful catalogue.
4. Add Nuvio-native source setup and watch-first Live TV guide in both appearance shells, using theme typography/colors/focus and localized strings. Integrate Home favourites/sports/recordings and Search without merging channels by name. Add the live route to navigation and account for live display ownership in the existing UI frame-rate governor.
5. Wire live session admission and player purpose before opening media, cancel VOD probes/thumbnails, and govern one audio/display owner. Build shared transport/capture, seekable local buffer and durable recording services next, preserving the full scope below. Admission is not a transport implementation and the existing app exit/kill path still needs capture-aware handling.

## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- Latest compile log: `validation/iptv-core/storage-app-final-compile.log`; earlier 68-test JVM build: `storage-app-build.log`; final device run: `storage-am9-final.txt` (74 tests, zero failures, 8.523 seconds). Generated logs/APKs remain ignored. Durable result/source hashes: `validation/IPTV-STORAGE-VALIDATION-20261005.json`.
- Device harness instructions: `tools/iptv-device-tests/README.md`. It builds actual production sources through Gradle Sync tasks and runs separately from the Nuvio app. Check the JUnit summary because adb can exit zero on a failed test run. Fixtures cover close/reopen, not abrupt process death or physical power loss; no recording/playback/performance claims.
- Full compile: `gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2`. Existing JVM suite: `:app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.*`. Redirect verbose baseline warnings to a log.
- Clean sibling checkout is also available at `E:/Codex/NuvioTV/work/NuvioTV-IPTV`; use its branch `codex/iptv`. The active chat's sandbox still covers the original Fork checkout, so sibling writes required escalation.
- This continuation staged only its own files under original `captures/iptv-storage/files`, copied using `captures/iptv-storage/sync.py`. Earlier staging remains under `captures/iptv-development`. Do not blindly resync either after editing sibling files: old staging can overwrite newer work.
- No AGENTS.md found in either checkout or applicable parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` continues hourly, at most ten runs, and must pause by 19:00 Brisbane on 5 October 2026 or when the user changes the plan. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.
