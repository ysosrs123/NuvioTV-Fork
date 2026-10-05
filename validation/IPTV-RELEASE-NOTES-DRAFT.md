# Nuvio IPTV — draft release notes

Unreleased development branch: codex/iptv. Updated 5 October 2026.
Base: 1.1.0-beta-nt4.1 / build 1458. Latest implementation: eaa43e5; this fresh-session checkpoint adds documentation only. This is a prototype, not a published release.

## Current checkpoint summary

- Implemented internal capture: bounded atomic spool and retention pins, independent viewer/recorder leases, one-body-at-a-time ingestion, finite local snapshots, and a bounded single-media-playlist HLS source with dedicated HTTP ownership.
- Latest fixes prevent capture from silently skipping missing media or accepting changed overlapping segments; recover responses lost on cancellation; retain failed manifest-close handles; and prevent swallowed/no-op HTTP close from falsely releasing ownership.
- Latest measured checks: full-app Kotlin compile, **176 IPTV JVM tests** and **190 Android backend tests** passed. Focused 130 core/10 HTTP checks also passed; all suites overlap. No new capture prototype APK was installed.
- These remain internal components. Pause/timeshift/recording, decoder-safe seeks and local live-player integration are not enabled. Broader capture protocols, durable services/schedules/storage, Stalker/automatic Xtream EPG, multiview/catch-up and final guide/Home/Search/UI remain outstanding.
- Component upgrades are a separate handoff to another agent; no dependency versions changed in this IPTV work.

## Live TV, sources and guide foundations

- Multiple independent M3U and Xtream Codes API live sources with Nuvio Original/V2 source forms, masked credentials and profile isolation.
- Bounded playlist parsing and refresh, stable source-scoped channel identities, favourites, hidden/custom-name overlays, transactional last-good retention and shrink review.
- Encrypted local credentials/catalogue and profile deletion cleanup. Xtream authentication, active-expiry checks, category/live-channel refresh and encoded credential components; no speculative media fetch on refresh.
- Multiple XMLTV HTTP/HTTPS feeds, gzip input, bounded XML parsing, cache validators, source/feed linking, manual channel mapping and precise-time programme summaries. XMLTV supplies listings; it is not a stream protocol.
- First Nuvio Live TV screen: preview, expanded video, 24-channel pages, source switching, favourites and focused-channel programme information. Focus does not open a stream.
- Foreground ExoPlayer playback with conservative provider/decoder/memory reservations; release must confirm before replacement. Background/profile/navigation cleanup and no automatic resume.
- Progressive MPEG-TS and HLS, including tested sliding TS-segment and byte-range fixtures. Durable per-channel Auto/HLS/MPEG-TS choice supports extensionless HLS when explicitly selected.
- Disabled automatic failed-load retries and alternate-HLS-rendition fallback. Fixed HTTP/1.0 metadata stale connection handling without enabling retries.

## Added in the latest development milestones

- IPTV HUD using the fork's existing HUD presentation with a dedicated live sampler: source video/audio format, buffer ahead, manifest live offset when available, HTTP body transfer rate/bytes, open requests, prepare-to-first-frame tune time, rebuffer count/time and renderer dropped buffers.
- XMLTV files can be selected through Android OpenDocument with a persistent read grant and refreshed through the bounded transactional importer. Malformed or inaccessible documents preserve last-good rows. Devices without a document picker show an explanatory message; AM9 currently has no system picker.
- Guide URLs can follow bounded HTTPS cross-origin redirects; downgrade and URL userinfo are rejected. Destination cache validators are neither forwarded nor stored against a short URL. Media redirect policy is unchanged.
- Native Nuvio audio/subtitle track dialog with Automatic, subtitle Off, current-track selection and player-reported unsupported tracks. Stale selections are checked against the current player; choices reset with the player. Controls alone are not codec certification.
- Shared HUD presentation now supports both the existing player and IPTV. Live counters reset per acquisition, separate startup from rebuffering, and report ongoing stalls and idle transfer rates. Toolbar controls wrap to accommodate the additional actions; remote/layout verification remains pending.
- Statistics never include provider URLs/credentials. Transfer rate is measured body consumption, not a speed test; live offset is not glass-to-glass latency; source frame rate is not HDMI output rate.
- Shared capture foundation now includes independently closing viewer/recorder ownership, a one-segment-at-a-time ingestion loop and a pinned local snapshot reader. These components are not yet enabled in the foreground player and do not constitute playable timeshift or recording.

## Correctness and reliability improvements

- Foreground replacement waits for both decoder and HTTP closure. Cancellation and stale screen owners cannot admit or stop the wrong session; uncertain releases retain reservations.
- Fixed two independently reproduced transport issues: stale HTTP/1.0 metadata connections and alternate HLS rendition requests after a failed segment despite disabled retry delays.
- Stream-format overrides persist through refresh/restart and schema migration, remain source/profile scoped, and invalidate stale browse cursors. Saving a format does not open media.
- Redirected guide refreshes isolate cache validators from the short URL, and inaccessible/malformed local guide replacements retain last-good programme data.
- Improved fixture tooling to reject stale UI dumps after a failed device capture. Added a synthetic document provider/picker for isolated grant testing; it does not supply a shipping file picker.

## Requested work still outstanding

- Complete local-file UI validation and an import alternative for TVs without a document picker; finish automatic Xtream EPG integration. The supplied tinyurl.com/epg-ss11 returned 404 for both bounded public HEAD and GET checks on 5 October; its guide contents are not validated.
- Stalker Portal source adapter and independently validated authentication/channel handling.
- Complete audio/subtitle preferences and representative rendering checks for subtitles, closed captions, DVB subtitles and teletext. Track-selection UI is implemented; these formats are not all certified.
- Connect the implemented shared-capture infrastructure to local pause/resume and seekable timeshift; implement durable recording/schedules to internal storage, USB and SMB.
- Broaden the initial bounded HLS capture adapter, prove decoder-safe entry points, implement a live local-reader/Media3 timeline and physical disk/free-space enforcement before enabling capture controls.
- Multiview with an independent source/playlist per pane, shared account groups and aggregate device/provider limits; provider catch-up.
- AFR/display handoff, integrated VOD/trailer/IPTV resource ownership, full guide grid, Home/Search, source/feed management, all supported locales and appearance/accessibility checks.

## Validation and known limitations

- At `0211656`: **112 JVM IPTV tests and 133 AM9 backend fixtures passed** (overlapping suites); full compilation/prototype assembly passed in 8m49s. Device fixture suite passed in 29.168s without network permission. Logs: iptv-core/hud-guides-build.log and hud-guides-am9.txt. Evidence: IPTV-HUD-GUIDES-VALIDATION-20261005.json. Previous video evidence remains in IPTV-HLS-VALIDATION-20261005.json.
- New AM9 HUD/track/document checks remain pending after earlier HDMI-sleep interruptions. The latest backend session observed AM9 awake without waking it; this does not certify screen availability or those UI paths. No HDMI/CEC/device settings changed. Initial HUD-build Live TV screen rendered before the earlier sleep.
- AM9 controlled video/UI checks cover progressive TS, sliding HLS, byte ranges, extensionless format selection, failure handling, explicit activation, replacement, favourites/paging and background cleanup. No real provider stress or credential copying.
- Synthetic 640×360/25 FPS H.264/silent AAC evidence is not audible output, HDR/Atmos, UHD decoder capacity or broad provider/codec certification.
- Current policy allows one foreground IPTV decoder and one acquisition for unknown/shared account capacity. These are estimates, not measured device limits.
- Media redirects remain rejected. No DRM, encrypted HLS, fMP4 or separate-audio certification. No finished timeshift/recording/multiview yet.
- Expanded video retains the prototype toolbar. English fallback strings remain; source form focus with an open IME needs further device checks.
- Original dirty fork and device apps/accounts/preferences/recordings are preserved. Prototype package: com.nuvio.iptv.prototype. Nothing published.

## Change history

196dcf4 ingest/admission; 9122fc6 catalogue/M3U transport; b8e60e9 XMLTV persistence; 84924e3 guide HTTP/cache; 53bd036 bounded browse/mapping; 7283bf5 source forms/profile cleanup; 7ec290a governed live playback; e636438 Xtream setup/HTTP1.0 fix; a9871c7 durable stream format/HLS fallback fix; 0211656 IPTV HUD/document guides/secure redirects/native tracks; 8f1316e bounded capture spool/reader retention/atomic publication; 4c3e0b0 shared capture ownership/sequential ingestion/finite local snapshot; eaa43e5 bounded HLS capture and HTTP closure fencing.

## Upstream review

Reviewed draft NuvioMedia/NuvioTV PR #3788 at e10c639: reproduced a preview direction-reversal seek error and a live-label delay discrepancy; documented retention/recovery concerns. Not imported. See IPTV-UPSTREAM-PR3788-REVIEW.md. Forthcoming timeshift tests must distinguish RAM load targets, actual playable windows and local capture retention.

## Capture foundation — internal, not yet a playable feature

- Added an exclusively owned, bounded segment spool with synced complete-segment/index publication through atomic moves, close/reopen recovery and scoped orphan cleanup. It refuses nonempty unowned directories, unexpected files and managed symlinks. Retained logical bytes, segment sizes/count and index parsing are bounded.
- Pause/recording anchors and open readers prevent eviction of retained media. Capacity exhaustion reports backpressure; gaps and discontinuities remain separate in actual retained bounds. Cancellation, oversized input and read failure before publication preserve committed data; failed cleanup blocks the next allocation until resolved. Closure is refused while readers/anchors remain.
- At `8f1316e`, seven focused filesystem tests passed separately on JVM (0.223s) and AM9 (0.39s), using the actual production class. The full 112/133 suites were not rerun at that checkpoint. The latest shared-capture section below records the subsequent full reruns. See IPTV-CAPTURE-STORE-VALIDATION-20261005.json for the historical evidence.
- Capture-to-player integration, durable schedules/leases and internal/USB/SMB recording are still unfinished; the latest internal shared/HLS transport is implemented below. File sync/atomic rename testing does not establish physical-power-loss durability. This store has not yet been included in the installed prototype APK. Extra staged-segment/index/filesystem space, physical disk allocation and decoder-safe seek entry points still need the transport/storage integration.

## Continuation record

Complete implementation context, reproduction locations, device cleanup state, remaining work and fresh-session instructions are saved in [the handover](IPTV-NEXT-SESSION-HANDOFF-20261005.md). The shared capture continuation builds on `27f78ea`; nothing has been published.

## Shared capture continuation — internal integration components

- Added a runtime that owns one producer/store across separate viewer and recording consumers. Closing a viewer leaves remaining consumers running. Uncertain decoder/reader closure retains that consumer's reservation; uncertain producer/store closure retains capture memory, account and full spool storage. Stale consumer tokens cannot close replacement acquisitions. Foreign acquisitions cannot be shared without their actual pipeline.
- Capture storage admission includes retained bytes, one staged segment and an explicit overhead margin. This remains a logical budget, not measured physical disk usage or a free-space guarantee.
- Added sequential segment ingestion with bounded store writes, body closure before the next pull, explicit backpressure/failure states and no automatic retry. Cancellation closes late bodies without publishing them; failed generic body closure remains retryable. The later HLS milestone adds a narrow protocol adapter; progressive TS and broader capture protocols remain unfinished.
- Added a finite local snapshot byte reader that pins before reading, crosses only contiguous segments, excludes future appends and retains its pin until explicit close. This is not yet a live-tail Media3 reader, a keyframe-safe seek window or a finished export feature.
- Twenty-four new regression tests cover sharing, final-consumer closure, cancelled joins/stops, failed construction/start/closure, foreign acquisitions, snapshot boundaries/retention, oversize input, backpressure, late bodies and blocked cleanup retries. Thirty-one focused JVM tests, including the original seven store tests, passed in 1.112s. Full app compilation and **143 JVM tests** passed; the final Android backend suite passed **164 tests in 30.233s** with no network permission. The suites overlap. Memory-related build/test-worker startup failures and the successful bounded reruns are recorded in the evidence report.
- AM9 was not woken. Temporary test packages were removed; pre-existing reverse8765 remains. The installed prototype remains the older HUD build. No new playback, UI, subtitle, document-picker, real-provider, power-loss or USB/SMB validation is claimed.
- See [the ownership/reader design and remaining integration requirements](IPTV-SHARED-CAPTURE-DESIGN.md) and `IPTV-SHARED-CAPTURE-VALIDATION-20261005.json`.

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
