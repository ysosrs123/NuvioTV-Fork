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

Earlier milestones: ingest/admission `196dcf4`, source/catalogue persistence and M3U HTTP `9122fc6`, transactional XMLTV `b8e60e9`, bounded guide HTTP/cache `84924e3`, bounded browsing/mapping `53bd036`, native source setup/profile cleanup `7283bf5`. This continuation connects admission to a real foreground Media3 player and adds a first Nuvio live screen.

**Live TV** is reachable from the source screen. The new `player/iptv` route has a video preview, 24-row channel pages, source switching, favourite toggling/filtering, focused-channel programme information and an expanded video view. Focusing a row reads guide information; activating it requests playback. The screen uses NuvioTheme and TV controls. The existing playback-visible route rules also apply to this route for UI scale/quality handling. Home/sidebar/Search integration, a full time-grid guide and complete appearance/locale certification remain.

`LivePlaybackRuntime` now serializes real player ownership. It holds account, decoder and memory reservations throughout closure and will not construct another player until the old handle confirms both decoder and request closure. Cancellation closes the previous player without admitting a replacement; stale screen owners cannot stop the new owner. Unknown account limits remain one acquisition. The current envelope is one foreground IPTV decoder with 16 MiB acquisition plus 96 MiB viewer estimates under a 192 MiB budget. These are conservative policy estimates, not measured native/hardware limits or global accounting across VOD/trailers/external players. Sharing with a capture consumer is deliberately denied until a shared transport exists.

The Media3 adapter receives explicit `LIVE_CHANNEL` purpose and bypasses the VOD probe/cache/prefetch/thumbnail helpers. Its loader factory fences late requests, counts in-progress connects, cancels its own HTTP calls at closure and waits for all tracked requests to close. A failed/uncertain release keeps reservations held. Inspection of Nuvio's bundled Media3 AAR confirmed that a release timeout is delivered synchronously through the player error listener; the adapter treats it as failure and avoids releasing reentrantly from listener dispatch. No competitor APK code was inspected or copied.

Live entry points expose unknown length to extractors even when the server provides Content-Length, and reject nonzero entry-point offsets. Distinct HLS segment URIs can still use byte ranges. Automatic load retries are explicitly disabled through the retry-delay policy; setting only the minimum retry count to zero was insufficient in the first local failure fixture. Media redirects are conservatively rejected. Foreground loss, navigation disposal, profile changes and player errors trigger cleanup; returning to foreground does not automatically reopen media. No AFR, pause/timeshift, catch-up, recording or multiview controls are claimed.

**All 120 AM9 backend fixtures passed in 29.025 seconds, and all 93 JVM IPTV tests passed.** The nine new tests exercise blocked closure, cancellation during replacement/preparation, startup failure, account capacity, rejected sharing, stale screen cleanup and request-fence idempotence. Suites overlap. Full-app compilation and the prototype build passed. See `IPTV-LIVE-VALIDATION-20261005.json` for the actual video/UI observations, HTTP request trace, APK/source hashes and limitations. Device video uses a generated 640x360/25 FPS H.264 transport stream with silent AAC; there is no real-provider, audible-output, HDR or decoder-capacity result.

AM9 remote checks passed for explicit playback, channel replacement, expanded video/Back, background closure, favourite toggling/filtering and cold-restart persistence. Paging 33 channels as 24 plus 9 works even when the old first row is scrolled out of composition, with first-row focus restored. A final APK favourite playback also rendered successfully. During replacement the local server briefly retained two writer handlers, but peer inspection at new-open time showed only the new live connection; real-provider quota cleanup latency remains unverified. The prototype is installed and stopped. Temporary test packages, this run's ADB reverse and UI dump were removed, and the controlled server was stopped. User apps/accounts were untouched.

Source/profile cleanup from the previous milestone remains in place. Source forms now clear the previous status when opening a new form. Programme times are shown only for precise timestamps. English resource fallback remains; no complete translation claim. Sources still share the conservative default account alias until explicit account-group UI exists.

## Next concrete implementation work

1. Complete the live player integration: native Nuvio transport/track/audio preferences, richer state/error handling, AFR/display handoff, decoder capability accounting and one aggregate owner across VOD/trailers/IPTV. Verify HLS, redirects and provider-specific transport behaviour with controlled fixtures before real-account trials. Only the generated progressive TS path is currently device-tested.
2. Build shared transport/capture ownership, seekable local buffering and durable internal/USB/SMB recording/scheduling. Keep reservations until the final real consumer closes; runtime currently rejects sharing. Make existing exit/kill paths capture-aware before background recording is enabled.
3. Extend Xtream HTTP authentication/account/category/live metadata. Current Xtream support remains a pure parser. Finish explicit shared account groups, source/feed deletion, priority/manual mapping, category facets, feed paging beyond 200 and durable rejected-candidate review.
4. Extend the first live screen with a proper time-grid guide, Home favourites/sports/recordings, Search and persistent focus anchors. Current pages requery every 30 seconds while foreground and restart on revision mismatch; event-driven invalidation remains. Never render from the whole-source catalogue snapshot.
5. Complete provider catch-up, governed multiview, all supported locales and appearance/scale validation. Full-screen currently expands the video while retaining the simple toolbar; it is not the finished immersive player UX. Retain independent channel identities even when names match.


## Validation and workspace commands

- Java: `E:/Android Studio/jbr`; Gradle cache: `D:/DevData/Gradle`; AM9 only: `192.168.10.60:5555`.
- Latest full compile: `validation/iptv-core/live-full-final-compile.log`; JVM suite: `live-final-build.log` (93 IPTV tests); device run: `live-am9.txt` (120 tests, zero failures, 29.025 seconds). Final prototype build: `live-focus-build.log`. Durable evidence/source hashes: `validation/IPTV-LIVE-VALIDATION-20261005.json`. Earlier evidence remains in its JSON files.
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

- Live runtime staging: original `captures/iptv-live/files`, copied with `captures/iptv-live/sync.py`. Do not resync old staging over later changes. Controlled media/server/UI evidence is in `captures/iptv-live`.
