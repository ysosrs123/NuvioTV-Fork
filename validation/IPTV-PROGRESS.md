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

Earlier milestones: ingest/admission `196dcf4`, source/catalogue persistence and M3U HTTP `9122fc6`, transactional XMLTV storage `b8e60e9`, bounded guide HTTP and retained-window cache checks `84924e3`. This continuation adds bounded channel browsing, Unicode-normalized name search, favourites/hidden/unavailable filters, explicit source-to-feed associations and priority, and a worker-dispatched browse/mapping repository. Catalogue schema v2 migrates names/overlays and adds browsing indexes, a revision counter and source/feed links. Credentials and identities are preserved.

Pages carry source/generation/configuration/overlay revisions. Changing the catalogue, overlays or feed links invalidates existing cursors rather than silently shifting rows. SQL first selects only bounded IDs; it then loads/decrypts at most the requested page plus one lookahead. A 401-row fixture proves a 10-row page opens only 11 encrypted records and complete paging visits each identity once. Search uses NFKC plus Locale.ROOT lowercase, with literal substring matching; this is not a claim of language-specific collation or full-scale search performance. `playbackItem` rechecks current source eligibility, hidden state and availability; it does not reserve/open playback.

Guide matching queries only the IDs needed by the current page, scoped to the profile and explicitly associated feeds. Duplicate IDs remain ambiguous unless priority/manual mapping is explicit. Missing/unlinked manual targets remain missing, with no name-based fallback. The repository validates feed ownership; low-level associations intentionally have no cross-database foreign key, so future deletion must preserve missing-target behaviour. Catalogue and guide reads are independent coherent snapshots: UI refresh/subscription work is still required.

**Final full app Kotlin compile passed; 109 AM9 fixtures passed in 26.531 seconds.** The existing **84 JVM IPTV tests also passed** during this continuation. Device coverage is 64 core, 13 catalogue storage, 11 guide storage, 2 playlist repository, 7 guide repository and 12 new browse/mapping cases. Suites overlap. Device APKs have no INTERNET permission; no provider/user-account requests were made. Both validation APKs were removed. No user-facing Nuvio APK was installed.

New checks cover bounded decryption/paging, Cyrillic/Japanese/fullwidth/custom-name search, literal search metacharacters, favourite order, hidden/tombstone filters, cursor invalidation, current playback lookup, source/profile isolation, same-ID feed ambiguity/priority/manual mapping, association persistence, foreign-feed rejection and v1 migration. Review also fixed the catalogue endpoint-change shrink baseline: a changed source configuration is no longer compared against its old endpoint's row count.

This remains backend foundation. **Nuvio-native source setup/guide UI, Xtream HTTP and actual live/capture ownership are not implemented.** Existing player callers default to VOD purpose; account/device admission is still bookkeeping. No provider interoperability, recording, abrupt process-death, full-scale performance or physical decoder/storage-capacity claim.

## Next concrete implementation work

1. Integrate source setup and bounded `IptvBrowseRepository.page` into Nuvio UI on worker dispatchers. Restart paging on `IptvCatalogueChangedException`; observe/requery guide updates. Use `playbackItem` immediately before purpose-aware admission rather than a stale UI row. `snapshot` still decrypts the entire source for import reconciliation/tests and must not be used for guide rendering. Group/category facets, cross-source Home/Search queries, source deletion/profile lifecycle and durable review of specific suspicious candidates remain. Account aliases must stay explicit/shared.
2. Wire existing guide feed listing, persistent associations, priority and manual overlays to source management. The production guide repository handles bounded HTTP, stale responses and retained-window conditional requests. Feed editing/deletion UI, durable rejected-candidate review and scheduled refresh/pruning remain. Imports retain at most 31 days; guide v2 forces full download outside retained coverage. Both stores' 256 MiB defaults bound logical pages only, not WAL/journal or total storage. Realistic EPG/catalogue performance remains unmeasured.
3. Extend transport for Xtream authentication/account/category/live metadata. Current Xtream support is a pure metadata parser; `IptvPlaylistRepository` explicitly reports unsupported source kinds. M3U HLS inputs are classified separately and never imported as channel rows. Invalid/partial/shrunken candidates retain the last working generation; HTTP validators activate only with a successful catalogue.
4. Add Nuvio-native source setup and watch-first Live TV guide in both appearance shells, using theme typography/colors/focus and localized strings. Integrate Home favourites/sports/recordings and Search without merging channels by name. Add the live route to navigation and account for live display ownership in the existing UI frame-rate governor.
5. Wire live session admission and player purpose before opening media, cancel VOD probes/thumbnails, and govern one audio/display owner. Build shared transport/capture, seekable local buffer and durable recording services next, preserving the full scope below. Admission is not a transport implementation and the existing app exit/kill path still needs capture-aware handling.

## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- Latest full compile: `validation/iptv-core/browse-app-final-compile.log`; JVM suite: `browse-app-build.log` (84 tests); device build: `browse-device-final-build.log`; final device run: `browse-am9-final.txt` (109 tests, zero failures, 26.531 seconds). Logs/APKs are ignored. Durable result/source hashes: `validation/IPTV-BROWSE-VALIDATION-20261005.json`; earlier evidence remains in its JSON files.
- Device harness instructions: `tools/iptv-device-tests/README.md`. It builds actual production sources through Gradle Sync tasks and runs separately from the Nuvio app. Check the JUnit summary because adb can exit zero on a failed test run. Fixtures cover close/reopen, not abrupt process death or physical power loss; no recording/playback/performance claims.
- Full compile: `gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2`. Existing JVM suite: `:app:testFullDebugUnitTest --tests com.nuvio.tv.core.iptv.* --tests com.nuvio.tv.data.iptv.*`. Redirect verbose baseline warnings to a log.
- Clean sibling checkout is also available at `E:/Codex/NuvioTV/work/NuvioTV-IPTV`; use its branch `codex/iptv`. The active chat's sandbox still covers the original Fork checkout, so sibling writes required escalation.
- This continuation staged only its own files under original `captures/iptv-browse/files`, copied using `captures/iptv-browse/sync.py`. Older staging is under `captures/iptv-guide-http`, `captures/iptv-guide`, `captures/iptv-storage` and `captures/iptv-development`. Do not resync old staging over newer sibling changes.
- No AGENTS.md found in either checkout or applicable parents. Do not repair the original checkout's bad Codex checkpoint ref as part of IPTV work.

## Full remaining scope

Multiple Xtream/M3U/M3U8 sources and EPG feeds; deterministic mapping and last-good refresh; guide, Home favourites/sports/recordings and existing Search; provider catch-up; internal/USB/SMB recording and durable schedules; local pause/resume; AFR; governed multiview; aggregate native/decoder/storage/provider limits; all Nuvio appearances and supported locales. A smaller milestone does not mark this scope complete.

## Continuation

Temporary heartbeat `nuvio-iptv-implementation-today` continues hourly, at most ten runs, and must pause by 19:00 Brisbane on 5 October 2026 or when the user changes the plan. Notify meaningful progress/failure/input needs. Any Nuvio prototype install uses a distinct package and label. Do not change working device preferences without recording/restoring them.
