# Nuvio IPTV — draft release notes

Unreleased development branch: codex/iptv. Updated 5 October 2026.
Base: 1.1.0-beta-nt4.1 / build 1458. This is a prototype, not a published release.

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
- Shared capture, local pause/resume and seekable timeshift; durable recording/schedules to internal storage, USB and SMB.
- Multiview with an independent source/playlist per pane, shared account groups and aggregate device/provider limits; provider catch-up.
- AFR/display handoff, integrated VOD/trailer/IPTV resource ownership, full guide grid, Home/Search, source/feed management, all supported locales and appearance/accessibility checks.

## Validation and known limitations

- At `0211656`: **112 JVM IPTV tests and 133 AM9 backend fixtures passed** (overlapping suites); full compilation/prototype assembly passed in 8m49s. Device fixture suite passed in 29.168s without network permission. Logs: iptv-core/hud-guides-build.log and hud-guides-am9.txt. Evidence: IPTV-HUD-GUIDES-VALIDATION-20261005.json. Previous video evidence remains in IPTV-HLS-VALIDATION-20261005.json.
- New AM9 HUD/track checks are pending: device wakes but HDMI puts it to sleep again (power service reports last sleep reason hdmi). No HDMI/CEC/device settings changed. Initial new-build Live TV screen rendered before sleep.
- AM9 controlled video/UI checks cover progressive TS, sliding HLS, byte ranges, extensionless format selection, failure handling, explicit activation, replacement, favourites/paging and background cleanup. No real provider stress or credential copying.
- Synthetic 640×360/25 FPS H.264/silent AAC evidence is not audible output, HDR/Atmos, UHD decoder capacity or broad provider/codec certification.
- Current policy allows one foreground IPTV decoder and one acquisition for unknown/shared account capacity. These are estimates, not measured device limits.
- Media redirects remain rejected. No DRM, encrypted HLS, fMP4 or separate-audio certification. No finished timeshift/recording/multiview yet.
- Expanded video retains the prototype toolbar. English fallback strings remain; source form focus with an open IME needs further device checks.
- Original dirty fork and device apps/accounts/preferences/recordings are preserved. Prototype package: com.nuvio.iptv.prototype. Nothing published.

## Change history

196dcf4 ingest/admission; 9122fc6 catalogue/M3U transport; b8e60e9 XMLTV persistence; 84924e3 guide HTTP/cache; 53bd036 bounded browse/mapping; 7283bf5 source forms/profile cleanup; 7ec290a governed live playback; e636438 Xtream setup/HTTP1.0 fix; a9871c7 durable stream format/HLS fallback fix; 0211656 IPTV HUD/document guides/secure redirects/native tracks; 8f1316e bounded capture spool/reader retention/atomic publication.

## Upstream review

Reviewed draft NuvioMedia/NuvioTV PR #3788 at e10c639: reproduced a preview direction-reversal seek error and a live-label delay discrepancy; documented retention/recovery concerns. Not imported. See IPTV-UPSTREAM-PR3788-REVIEW.md. Forthcoming timeshift tests must distinguish RAM load targets, actual playable windows and local capture retention.

## Capture foundation — internal, not yet a playable feature

- Added an exclusively owned, bounded segment spool with synced complete-segment/index publication through atomic moves, close/reopen recovery and scoped orphan cleanup. It refuses nonempty unowned directories, unexpected files and managed symlinks. Retained logical bytes, segment sizes/count and index parsing are bounded.
- Pause/recording anchors and open readers prevent eviction of retained media. Capacity exhaustion reports backpressure; gaps and discontinuities remain separate in actual retained bounds. Cancellation, oversized input and read failure before publication preserve committed data; failed cleanup blocks the next allocation until resolved. Closure is refused while readers/anchors remain.
- Seven focused filesystem tests passed separately on JVM (0.223s) and AM9 (0.39s), using the actual production class. The previous full 112/133 suites were not rerun after this addition; do not combine the counts into a whole-app validation claim. See IPTV-CAPTURE-STORE-VALIDATION-20261005.json.
- Capture transport, seekable player integration, durable schedules/leases and internal/USB/SMB recording are still unfinished. File sync/atomic rename testing does not establish physical-power-loss durability. This store has not yet been included in the installed prototype APK. Extra staged-segment/index/filesystem space, physical disk allocation and decoder-safe seek entry points still need the transport/storage integration.

## Continuation record

Complete implementation context, reproduction locations, device cleanup state, remaining work and fresh-session instructions are saved in [the handover](IPTV-NEXT-SESSION-HANDOFF-20261005.md). This draft covers committed implementation through `8f1316e`; nothing has been published.
