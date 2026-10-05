# Nuvio IPTV continuation handover — 5 October 2026

## Start here

The user explicitly requested a fresh chat with complete context, updated comprehensive draft release notes, and continued implementation. Continue actual implementation autonomously after reading this handover; this is not a request to stop at a plan. The overall IPTV scope is unfinished.

**Authoritative implementation checkout:** `E:/Codex/NuvioTV/work/NuvioTV-IPTV`, branch `codex/iptv`. Its equivalent C: path is `C:/Users/PWR/Documents/Codex/2026-09-13/prior-conversation-with-codex-conversation-role/work/NuvioTV-IPTV`. Source HEAD at handover preparation is `8f1316e`; a documentation-only handover commit follows. Base is published nt4.1 `574a2d41257ccc667828f54ba0c6ef40d9784984`, build 1458. Verify branch/status before editing.

The saved Codex project points to the ORIGINAL sibling `NuvioTV-Fork`, not the implementation checkout. Its dirty `feature/nuvio-v2-ui` branch and broken Codex checkpoint ref must remain untouched: no reset, repair, stash, or commits there. Only ignored task scratch under its `captures/` has been added. Do not accidentally implement in that original project. No AGENTS.md was found in either checkout or applicable parents during this work; obey any subsequently added instructions.

Read `validation/IPTV-PROGRESS.md`, `IPTV-RELEASE-NOTES-DRAFT.md`, the latest HUD/capture validation JSON, and `IPTV-UPSTREAM-PR3788-REVIEW.md`. This handover resolves historical present-tense entries in progress; the dated milestone reports preserve earlier evidence.

## User requirements and authorization

- Continue everything: multiple M3U/M3U8 and Xtream Codes API sources; multiple XMLTV feeds from `.xml`/`.xml.gz` documents and URLs including shortened links; Stalker Portal; deterministic identities/refresh/mapping; purpose-aware live playback and account/device reservations.
- Adapt the fork's existing ExoPlayer HUD to IPTV with honest IPTV-specific metrics. Support subtitles, CC, DVB subtitles and teletext. Add shared capture, local pause/resume, timeshift, record-now, durable schedules and internal/USB/SMB storage.
- Govern multiview, allowing a different source/playlist in each pane, and provider catch-up. Integrate full guide, Home favourites/sports/recordings and Search, AFR and supported appearances/locales.
- Watch-first UX. User-facing UI must use Nuvio Original/V2 themes, fonts, styles, focus/navigation and preferences. TiviMate/NoBuffr are functional references; independently validate their behaviour, never assume correctness, and do not extract/copy competitor APK code or UI.
- AM9 use and NoBuffr installation are authorized; user signed into NoBuffr. Preserve apps, accounts, preferences and recordings. No root/security changes, publication, messages to others, provider credential extraction/copying, or unattended provider stress. Use distinct prototype package/label and conservative unknown concurrency.
- Keep comprehensive draft release notes current. Distinguish implemented code, measured validation, untested UI and future support.
- User returned, then authorized continued work. Temporary automation `nuvio-iptv-implementation-today` is **PAUSED**. Do not resume it or the older research automation. No new automation requested. Continue in the new chat.

## Current result and architecture

Commits: `196dcf4` ingest/admission; `9122fc6` catalogue/M3U; `b8e60e9` XMLTV persistence; `84924e3` bounded guide HTTP/cache; `53bd036` browse/mapping; `7283bf5` forms/profile cleanup; `7ec290a` foreground playback; `e636438` Xtream/HTTP1.0 fix; `a9871c7` stream format/HLS fallback fix; `0211656` HUD/document guides/track controls; `8f1316e` bounded capture store.

Core/data live under `app/src/main/java/com/nuvio/tv/{core,data}/iptv`, screen/runtime under `ui/screens/iptv`. Inspect the current source for exact APIs.

Ingest/persistence: bounded strict M3U, source-scoped stable IDs, reconciliation/tombstones, last-good transactions and shrink review; encrypted SQLite credentials/catalogue using Android Keystore, schema 3. Channel overlays include favourite/hidden/custom name/manual guide and durable AUTO/HLS/MPEG_TS format. Profile session fences revoke access before cleanup. Browse is paged (24 UI rows, max 200), revision-fenced, Unicode NFKC/ROOT searchable. Do not render from whole-source catalogue snapshots. All sources currently use conservative `shared-default` account alias pending account-group UI.

Xtream refresh performs sequential authentication/categories/live metadata requests, active/expiry checks, strict UTF-8/JSON and bounded budgets, with no media/logo/direct_source/advertised-host fetch. Components are encoded, advertised connection limits never raise capacity, TS preferred and explicitly advertised HLS accepted when TS absent. Stable IDs preserve overlays across credential changes, but edits invalidate playback until refresh commits. VOD/series and automatic Xtream EPG/API guide integration remain unimplemented. Metadata clients request Connection: close after an independently reproduced HTTP/1.0 stale pooled connection bug; no automatic retries.

XMLTV: bounded compressed/expanded input, no external entities, timestamp precision and feed-scoped mapping, transactional promotion, cache validators and last-good retention. `0211656` adds encrypted content-URI endpoints, Android OpenDocument selection, persisted read grant on Save and ContentResolver refresh. Missing/revoked permission or bad replacement retains previous guide. Bounded redirects (six) permit same-origin HTTP and cross-origin HTTPS; reject downgrade/userinfo/fragments, omit cookies/auth/referrer and clear validators across redirects to prevent short-URL retarget errors. Media redirects remain rejected. User's exact `https://tinyurl.com/epg-ss11` returned 404 for HEAD and bounded GET on 5 October; no guide was obtained. XMLTV supplies listings, not playable channels by itself.

Foreground player: explicit LIVE_CHANNEL purpose bypasses VOD probes/cache/prefetch/thumbnails. `LivePlaybackRuntime` mutex holds provider/decoder/memory reservations until actual decoder plus request closure; uncertain release retains reservations, stale owners cannot stop new player. Sharing is rejected until shared transport exists. Policy is one foreground IPTV decoder, one unknown-capacity acquisition; 16 MiB acquisition + 96 MiB viewer estimates under 192 MiB budget, not measured hardware/global VOD limits. `LiveRequestFence` fences late opens, counts connects, cancels client and waits for closure. Adapter uses Media3 ExoPlayer, dedicated OkHTTP (10s timeouts, no redirect/retry), conservative load control. Entry-point unknown length/nonzero-offset rejection prevents TS tail probes; separate HLS segments can use byte ranges. Both retry-delay and fallback-selection must be disabled: Media3 otherwise opened alternate HLS rendition after a 503. Explicit format selection handles extensionless HLS without probing and applies only on next activation.

Nuvio Live TV currently offers preview, expanded video, paged channels/source cycle/favourites and focused-channel programme details. Focus alone does not open media. Profile/navigation/background/error closes ownership; return foreground does not reopen automatically. Expanded player still retains prototype toolbar. Full guide, Home/Search and final immersive UX remain. Source-form IME focus needs additional checking despite earlier password-to-Save fix.

HUD/track milestone `0211656`: reuses extracted `DebugStatsPanel` presentation; `LiveTelemetry` counters synchronized, player reads main-thread, HUD sampled once a second while visible. Shows source formats, buffer ahead, manifest offset or unavailable, HTTP body bytes/rate, active requests, prepare-to-first-frame latency, rebuffer count/duration and dropped renderer buffers. No URLs/credentials, inferred file size, speed-test/glass-to-glass/HDMI claims. Native `IptvTrackDialog` exposes supported audio/text tracks, Automatic/subtitle Off, selected/unsupported states and stale-group check; resets with player. Track controls are not CC/DVB/teletext rendering certification. Toolbar FlowRow and track/HUD remote UI still need device validation.

Capture foundation `8f1316e`: `CaptureSegmentStore` and tests. A private owned directory with exclusive FileChannel lock; refuses nonempty unmarked roots/symlinks/unexpected entries. Bounded retained logical bytes/segment size/count (4096), bounded index (1 MiB), complete caller-supplied segments only. Pending segment is synced then atomically moved; synced index atomically replaces old snapshot before retiring old files. Precommit cancel/read failure/oversize preserves committed rows; postcommit cleanup failure requires cleanup before next allocation. Pins protect selected sequence and successors; open readers keep their own pin; protected capacity returns backpressure, not eviction. Close refuses live consumers. Latest continuous bounds use exclusive end and exclude gaps/discontinuities. This is **not playable timeshift/recording**, decoder/keyframe safety, persistent leases or power-loss certification. One pending segment plus index/filesystem overhead needs extra space reservation; physical allocation/free-space layer still needed. No transport adapter, player reader, capture service, schedule or USB/SMB integration yet.

## Validation and limits

- Full application compile/prototype build and **112 JVM tests** passed at HUD/guide milestone (8m49s). **133 AM9 backend fixtures** passed (29.168s), using actual Android JSON/SQLite/Keystore/parser, no network permission. Suites overlap. Evidence `IPTV-HUD-GUIDES-VALIDATION-20261005.json`; logs `validation/iptv-core/hud-guides-build.log`, `hud-guides-am9.txt`.
- Capture store separately passed **7 focused JVM tests** (0.223s) and **7 AM9 tests** (0.39s), actual production source compiled by Android harness (18s build). Evidence `IPTV-CAPTURE-STORE-VALIDATION-20261005.json` and `iptv-core/capture-*`. Do not claim a combined full 119/140 suite rerun; full application was not rebuilt after this new class.
- Installed prototype derives from `0211656`; capture foundation is not in that APK. Initial new-build Live TV UI rendered, then HDMI sleep blocked HUD/track/local-document screen checks. No new HUD rendering/subtitle playback/persisted-grant UI success claimed.
- Earlier LIVE/XTREAM/HLS evidence JSON records AM9 progressive TS, sliding HLS TS, byte ranges, extensionless format choice, explicit playback/replacement/favourites/paging/background cleanup and Original/V2 format dialogs. Media is generated 640x360/25fps H.264 with silent AAC: no audibility, HDR/Atmos, UHD capacity or broad provider/codec certification.
- No abrupt process-kill/power-loss recovery, provider quota cleanup latency, broad HLS (fMP4, encryption/DRM, separate audio, redirects/ABR), full translations or aggregate VOD/trailer/IPTV resource certification.

## AM9 state, blockers and careful reproduction

AM9 serial **192.168.10.60:5555**, AM9PRO Android 14/API34. Always `adb -s`; another device at .172 exists and is out of scope. Prototype `com.nuvio.iptv.prototype`, label Nuvio IPTV Prototype, installed and force-stopped. Validation packages `com.nuvio.iptv.validation` and `.validation.test` removed. This run's reverse tcp18767 removed; pre-existing tcp8765 preserved. All fixture servers stopped, own device UI dump removed. Device left asleep as initially found. No root/CEC/power/security changes.

Waking with key224 was followed ~15s later by HDMI sleep; `dumpsys power` last sleep reason `hdmi`, screen timeout 1h. Pending async question (not answered): can user leave connected TV/AVR on, or continue without screen tests? Do not repeatedly wake/change CEC; continue backend independently until screen is available.

AM9 lacks native OPEN_DOCUMENT/GET_CONTENT handlers. Shipping import UI gracefully explains this, but a real alternative remains necessary. Test-only `FixturePickerActivity`/`FixtureGuideProvider` in instrumentation APK supplies one synthetic XMLTV document with read/persistable grant, no personal-file browsing. Built/installed then removed, not exercised. Keep INTERNET removal in both manifests; test APK allowed REORDER_TASKS only. This fixture does not fix product file access.

Prototype QA activity: `am start -n com.nuvio.iptv.prototype/com.nuvio.tv.IptvPrototypeActivity --ez live true --es appearance v2`; omit appearance for Original. Force-stop first when changing extras. Existing sources synthetic only: FixturePlaylist-Edited (33 M3U); XtreamFixture (2, fixture/fixture-pass on localhost18766); HlsFixture (4 on localhost18767). Guide fixture expired around 15:00 Brisbane; refresh generated dates for new checks. HLS Extensionless overlay set HLS; others Auto. Existing favourites retained.

Old `captures/iptv-hud/next-source.xml` is **stale/invalid evidence** after uiautomator failed; helper now deletes its previous dump, requires successful fresh dump text and uses 25s timeout. Initial awake screenshot is valid; black sleep captures are not playback failures.

TiviMate 5.4.0 Beta2/code1000005402: prior single FHD50 test switched fullscreen output50Hz and retained preview50, restored AFR off and Home59.94. Not proof of all fixes/rates/sinks. NoBuffr1.0.0/210271: signed in, 13,740 channels; one UHD sports app-reported2160p50/5.1, 1GB timeshift, paused39s. Resume inconclusive (Up changed channel); no record/multiviewcapacity testing. Both comparator apps stopped, accounts/preferences preserved. Research is complete enough to implement; do not repeat it unnecessarily.

## Upstream PR #3788

User explicitly requested review. Report: `validation/IPTV-UPSTREAM-PR3788-REVIEW.md`. Open draft “Improve live TV buffer stability, player UI, seek controls”, inspected head `e10c639200d5821d1cbc71bdd438ccbd6b1ff7ff`, base dev `5c1d9b0e2669199114a12ade38027da132303eb3`. Status may change; use pinned revision for these findings. No merge/cherry-pick/install/comment/review posted.

Exact policy compiled unchanged in isolated Kotlin JVM with unrelated settings stubs. P1: player50s/buffer53, -30 preview20, then +10 with pending20/displaydelay30 selects60 rather than30 because pending anchor applies only to negative deltas; caller confirmed actual position unchanged until commit. P2: measured manifest offset20s can display LIVE because hardcoded3s baseline and locked filter suppress delay. Recovery design conflates RAM load target with actual playable DVR/local window (`shouldRejoinLiveEdge(90000,45000,1000)=true`); default-position/reload also needs our governed acquisition path. Carry useful focus/scrub/explicit-return-live requirements forward, independently implement actual bounds. This was focused review, not full PR/device certification.

## Next concrete work

1. Wire bounded capture transport and independent capture/viewer ownership, keeping account reservation until final real consumer closes. Establish decoder-safe entry points/actual retained seek bounds, then local reader/pause/resume/backpressure/expiry UX. Store alone is not recording. Add meaningful adapter/ownership/boundary tests; include PR direction-reversal and misleading-LIVE regressions when implementing scrubbing.
2. When TV/AVR available, complete HUD preview/expanded/replacement/background checks; Original/V2 track focus, WebVTT select/off/render; document fixture Save/Refresh/coldrestart/revocation; source-form IME/focus and FlowRow. Preserve device state/cleanup. Native file-import alternative remains necessary even if test picker passes.
3. Durable record-now/schedules/leases, capture-aware exit/kill paths, foreground service and process-death reconciliation; internal/USB/SMB delivery/free-space/physical allocation validation.
4. Stalker Portal using controlled protocol fixtures and user-configured credentials only; Xtream automatic XMLTV/API EPG; explicit account groups, source/feed management and guide priority/mapping/facets/paging/rejected-candidate review.
5. Governed multiview across different sources per pane, aggregate VOD/trailer/IPTV reservations, single audio/display/AFR owner, provider catch-up.
6. Complete preferences and representative CC/DVB/teletext/subtitle fixtures, broader controlled transport/hardware coverage, then guide/Home/Search, final immersive UX, appearances/locales/accessibility. Keep release notes and evidence honest and current.

Work around screen/provider blockers; do not halt all implementation awaiting hardware UI. No new subagents unless user or applicable instructions explicitly requests delegation.

## Toolchain, commands and scratch

Windows PowerShell, Brisbane timezone. JDK `E:/Android Studio/jbr`, Gradle cache `D:/DevData/Gradle`, Android SDK `E:/DevData/Android/Sdk` (`ANDROID_HOME`), AAPT build-tools/36.1.0. Python `C:/Users/PWR/.cache/codex-runtimes/codex-primary-runtime/dependencies/python/python.exe`; ffmpeg/ffprobe on PATH. Explicit UTF-8 file I/O (Windows default can be cp1252). `gh` unavailable; GitHub connector works. CODEX_HOME env was unset; actual directory `C:/Users/PWR/.codex`.

Run in implementation sibling with task-specific env vars, JAVA_HOME/GRADLE_USER_HOME as needed:

```powershell
./gradlew.bat :app:compileFullDebugKotlin --offline --no-daemon --max-workers=2
./gradlew.bat :app:testFullDebugUnitTest --tests 'com.nuvio.tv.core.iptv.*' --tests 'com.nuvio.tv.data.iptv.*' --offline --no-daemon --max-workers=2
./gradlew.bat -p tools/iptv-device-tests assembleDebug assembleDebugAndroidTest --offline --no-daemon --max-workers=2
```

Redirect verbose baseline warnings to log; full builds take5–9min. See harness README for install/instrument/uninstall commands, check actual JUnit summary (adb may exit0 for failed tests). Do not assume suite sums are independent. Prototype build task/flavor is documented in prior validation logs; inspect before selecting.

Current chat sandbox covers original Fork, not sibling. Sibling writes/builds/commits used concrete authorized `require_escalated` exec requests, approved by automatic review. Do not move work back into original to avoid sandbox. Staging with apply_patch in original ignored captures then guarded copy/elevated script is available. No rejected permission requests remain.

Original ignored scratch: `captures/iptv-hud` (fixture.py --directory captures/iptv-hud --subtitles for loopback18767, generated90sTS/WebVTT, fresh-dump device.py), `iptv-guides-local`, `iptv-capture` (standalone compiler runner), `pr3788` (pinned policies/repro harness/results), earlier iptv-hls/xtream/live/setup. Never rerun prepare/sync scripts or copy staged old source over newer authoritative sibling. The capture run-tests.ps1 currently compiles STAGED source: point future runs at authoritative source before relying on it for new changes. Committed HLS fixture is in tools/iptv-device-tests/hls_fixture.py; private WebVTT extension has not been promoted there yet.

Completed research: original `validation/iptv-research/NEXT-SESSION-HANDOFF.md` and design/evidence files. Use as requirements/context, not code to copy. No fixture servers, background shell jobs, active subagents or goals remain at handover. New chat should inspect current state before starting tests.
