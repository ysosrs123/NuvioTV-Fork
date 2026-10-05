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

Earlier milestones: ingest/admission `196dcf4`, source/catalogue persistence and M3U HTTP `9122fc6`, transactional XMLTV storage `b8e60e9`, bounded guide HTTP/cache checks `84924e3`, bounded browsing/mapping `53bd036`. This continuation adds Nuvio-native source setup, profile lifecycle integration and a distinct prototype build.

Settings now routes to **Live TV sources**. The screen adds/edits M3U playlist URLs and XMLTV feed URLs, explicitly refreshes each, selects a source and links/unlinks its guides. It uses NuvioTheme, shared SettingsGroupCard and NuvioDialog, TV buttons and resource-backed text. Endpoints are masked, retained only in the active edit form, and not saved to SavedState or logged. Network/store work runs off the UI thread. Profile changes cancel existing work, clear displayed data and discard old edit forms. New sources share the conservative `shared-default` account alias; explicit account grouping UI remains.

The Hilt module integrates IPTV stores with existing profile credential cleanup. Removing a profile revokes its active UI sessions before deleting sources, identities, catalogue rows, overlays, guide associations, feeds and staged/active guide data. A late save through an old session is rejected. Other profiles remain intact; global cleanup invalidates all existing sessions. Two AM9 fixtures prove these paths, including deliberate reuse of a retired profile ID. Cleanup spans two databases and is not a cross-database atomic transaction; existing ProfileManager deletion does not complete if cleanup throws.

**Full app Kotlin compile passed; all 92 selected JVM tests passed (84 IPTV + 8 settings), and all 111 AM9 fixtures passed in 27.237 seconds.** Suites overlap. The backend fixture packages have no INTERNET permission. The separate `iptvPrototype` flavor includes full plugin sources/dependencies, uses package `com.nuvio.iptv.prototype`, labels every launcher icon **Nuvio IPTV Prototype**, and disables in-app update checks. Its direct QA activity is absent from normal full builds. See `IPTV-SETUP-VALIDATION-20261005.json` for actual visual/device evidence and limitations.

The existing bounded browse API still limits decryption to page size plus one, invalidates cursors after catalogue/configuration/overlay/link changes, and scopes guide mapping to explicitly linked feeds. Never use whole-source `snapshot` to render the guide. Catalogue and guide reads remain independent snapshots requiring UI requery. The normal app's runtime OkHttp version resolves to 5.3.2 through NiceHttp, matching the backend fixture harness.

**Source management is implemented; watch-first guide and live playback are not.** Existing player callers still default to VOD purpose, and admission is bookkeeping. English strings currently provide fallback; translations and complete appearance/locale certification remain. No provider interoperability, recording, abrupt process-death, full-scale performance or physical decoder/storage-capacity claim.

## Next concrete implementation work

1. Build the watch-first Live TV guide with bounded IptvBrowseRepository pages, refreshing pages on catalogue/guide changes. Use playbackItem immediately before purpose-aware admission. Add a native live route and account for live ownership in the display governor. The current source screen is reached through Settings; it is not the final watching workflow.
2. Complete source/feed management: deletion with lifecycle cleanup, explicit shared account groups, feed priority/manual mapping, category facets, feed paging beyond 200 and durable rejected-candidate review. Both stores' 256 MiB limits cover logical pages, not WAL/journal/total disk. Guide imports retain at most 31 days; UI refresh currently requests a stable UTC-day window from yesterday through seven days ahead.
3. Extend transport for Xtream authentication/account/category/live metadata. Current Xtream support is a pure parser; playlist repository rejects unsupported source kinds. M3U HLS inputs are classified separately and never imported as channel rows. Invalid/partial/shrunken candidates retain the last working generation.
4. Wire live admission and player purpose before media opens, cancel VOD probes/thumbnails, govern a single audio/display owner, then build shared transport/capture, seekable local buffering and durable internal/USB/SMB recording services. Existing app exit/kill behaviour still needs capture-aware handling.
5. Complete Home favourites/sports/recordings, Search, catch-up, AFR, governed multiview, all supported locales and appearance validation. Preserve independent channels even when names match. Complete end-to-end watching work before expanding cosmetic setup options.


## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- Latest full compile: `validation/iptv-core/setup-app-compile.log`; JVM suite: `setup-app-tests.log` (92 tests: 84 IPTV + 8 settings); device run: `setup-am9.txt` (111 tests, zero failures, 27.237 seconds). Prototype build: `setup-prototype-final-build.log`. Durable evidence/source hashes: `validation/IPTV-SETUP-VALIDATION-20261005.json`; earlier evidence remains in its JSON files.
- Device harness instructions: `tools/iptv-device-tests/README.md`. It builds actual production sources through Gradle Sync tasks and runs separately from the Nuvio app. Check the JUnit summary because adb can exit zero on a failed test run. Fixtures cover close/reopen, not abrupt process death or physical power loss; no recording/playback/performance claims.
- Full compile: `gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2`. Existing JVM suite: `:app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.*`. Redirect verbose baseline warnings to a log.
- Clean sibling checkout is also available at `E:/Codex/NuvioTV/work/NuvioTV-IPTV`; use its branch `codex/iptv`. The active chat's sandbox still covers the original Fork checkout, so sibling writes required escalation.
- This continuation staged only its own files under original `captures/iptv-browse/files`, copied using `captures/iptv-browse/sync.py`. Older staging is under `captures/iptv-guide-http`, `captures/iptv-guide`, `captures/iptv-storage` and `captures/iptv-development`. Do not resync old staging over newer sibling changes.
- No AGENTS.md found in either checkout or applicable parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` continues hourly, at most ten runs, and must pause by 19:00 Brisbane on 5 October 2026 or when the user changes the plan. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.

- Source setup staging: original `captures/iptv-setup/files`, copied with `captures/iptv-setup/sync.py`. Never resync older staging over later sibling edits. Private prototype screenshots and UI fixture helper are in `captures/iptv-setup`.
