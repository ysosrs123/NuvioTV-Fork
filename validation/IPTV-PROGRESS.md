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

Implemented and compiled: bounded M3U/UTF-8 catalogue parsing with HLS classification; Xtream metadata interpretation; source-scoped channel identity and stale/partial refresh admission; account/device/shared-consumer reservations; streaming XMLTV parsing; explicit feed-scoped guide matching; cancellable bounded HTTP playlist downloads; and purpose-aware VOD cache/chunk/prewarm/thumbnail gates.

**Full Android build and 68 IPTV tests passed** (`:app:compileFullDebugKotlin :app:testFullDebugUnitTest`, offline). This includes nine local HTTP server tests for redirects, conditional requests, partial responses, compressed/chunked byte limits, cancellation, and HLS non-import. The standalone core runner covers 59 of these; HTTP tests need Gradle. This is foundation code, not a functioning user-facing IPTV feature yet. Existing VOD callers keep their default purpose. No new Nuvio APK was installed.

## Next concrete implementation work

1. Add profile-scoped source/account setup and transactional persistent catalogue/guide storage. Keep encrypted credentials and playback locators out of logs, navigation routes and sync payloads. Preserve independent user overlays and tombstones. Implement staging generations, compare configuration/request tickets inside the commit transaction, and attach HTTP validators only to successfully activated data. Add integration tests for cancellation, failure/low disk, stale refresh and process reopen.
2. Wire the existing `IptvMetadataClient` and pure parsers into that repository. XMLTV parser emits staging callbacks; never directly publish them. Guide import still needs bounded gzip transport, storage, retention and query indexes. Xtream has metadata parsing only; authentication/account/category/live HTTP adapters remain.
3. Add Nuvio-native Live TV route/source management/watch-first guide in both appearance shells, with theme typography/colors/focus and localized strings. The new core has no UI yet. Integrate Home favourites/sports/recordings and Search without joining same-name channels across sources.
4. Wire a live session owner through admission and player purpose before any live request. Current admission is atomic bookkeeping, not an upstream/capture service. Enforce closure acknowledgement, global VOD activity cancellation and one audio/display owner. Build real shared capture, seekable local buffer and recording service/schedules next; preserve the full scope below.

## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`.
- Latest build log: `validation/iptv-core/android-build-68.log`; authoritative JUnit XML: `app/build/test-results/testFullDebugUnitTest`. 68 tests, zero failures/errors.
- Build command: `gradlew.bat :app:compileFullDebugKotlin :app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.* --offline --no-daemon --max-workers=2 -Dorg.gradle.jvmargs=-Xmx4096m ...`. Redirect verbose baseline warnings to a log.
- Original checkout's `captures/iptv-development/files` was temporary staging because this chat's write sandbox covers the original only. `captures/iptv-development/sync.py` copies it to the clean sibling with escalation. Do not blindly resync after changing sibling files: stale staging can overwrite newer work. Generated `validation/iptv-core` files are skipped by the helper and should not be committed.
- No AGENTS.md found in either checkout or relevant parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` continues hourly, at most ten runs, and must pause by 19:00 Brisbane on 5 October 2026 or when the user changes the plan. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.
