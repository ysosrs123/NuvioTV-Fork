# Nuvio IPTV — draft release notes

Unreleased development branch: codex/iptv. Updated 5 October 2026.
Base: 1.1.0-beta-nt4.1 / build 1458. This is a prototype, not a published release.

## Implemented before this continuation

- Multiple independent M3U and Xtream Codes API live sources with Nuvio Original/V2 source forms, masked credentials and profile isolation.
- Bounded playlist parsing and refresh, stable source-scoped channel identities, favourites, hidden/custom-name overlays, transactional last-good retention and shrink review.
- Encrypted local credentials/catalogue and profile deletion cleanup. Xtream authentication, active-expiry checks, category/live-channel refresh and encoded credential components; no speculative media fetch on refresh.
- Multiple XMLTV HTTP/HTTPS feeds, gzip input, bounded XML parsing, cache validators, source/feed linking, manual channel mapping and precise-time programme summaries. XMLTV supplies listings; it is not a stream protocol.
- First Nuvio Live TV screen: preview, expanded video, 24-channel pages, source switching, favourites and focused-channel programme information. Focus does not open a stream.
- Foreground ExoPlayer playback with conservative provider/decoder/memory reservations; release must confirm before replacement. Background/profile/navigation cleanup and no automatic resume.
- Progressive MPEG-TS and HLS, including tested sliding TS-segment and byte-range fixtures. Durable per-channel Auto/HLS/MPEG-TS choice supports extensionless HLS when explicitly selected.
- Disabled automatic failed-load retries and alternate-HLS-rendition fallback. Fixed HTTP/1.0 metadata stale connection handling without enabling retries.

## Current continuation — builds and backend tests passed; screen checks pending

- IPTV HUD using the fork's existing HUD presentation with a dedicated live sampler: source video/audio format, buffer ahead, manifest live offset when available, HTTP body transfer rate/bytes, open requests, first-frame tune time, rebuffer count/time and renderer dropped buffers.
- XMLTV files can be selected through Android OpenDocument with a persistent read grant and refreshed through the bounded transactional importer. Malformed or inaccessible documents preserve last-good rows. Devices without a document picker show an explanatory message; AM9 currently has no system picker.
- Guide URLs can follow bounded HTTPS cross-origin redirects; downgrade and URL userinfo are rejected. Destination cache validators are neither forwarded nor stored against a short URL. Media redirect policy is unchanged.
- Native Nuvio audio/subtitle track dialog with Automatic, subtitle Off, current-track selection and player-reported unsupported tracks. Controls alone are not codec certification.
- Statistics never include provider URLs/credentials. Transfer rate is measured body consumption, not a speed test; live offset is not glass-to-glass latency; source frame rate is not HDMI output rate.

## Requested work still outstanding

- Complete local-file UI validation and an import alternative for TVs without a document picker; finish automatic Xtream EPG integration. The supplied tinyurl.com/epg-ss11 returned 404 for both bounded public HEAD and GET checks on 5 October; its guide contents are not validated.
- Stalker Portal source adapter and independently validated authentication/channel handling.
- Complete audio/subtitle preferences and representative rendering checks for subtitles, closed captions, DVB subtitles and teletext. Track-selection UI is implemented; these formats are not all certified.
- Shared capture, local pause/resume and seekable timeshift; durable recording/schedules to internal storage, USB and SMB.
- Multiview with an independent source/playlist per pane, shared account groups and aggregate device/provider limits; provider catch-up.
- AFR/display handoff, integrated VOD/trailer/IPTV resource ownership, full guide grid, Home/Search, source/feed management, all supported locales and appearance/accessibility checks.

## Validation and known limitations

- Current: **112 JVM IPTV tests and 133 AM9 backend fixtures pass** (overlapping suites); full compilation/prototype assembly passed in 8m49s. Device fixture suite passed in 29.168s without network permission. Logs: iptv-core/hud-guides-build.log and hud-guides-am9.txt. Evidence: IPTV-HUD-GUIDES-VALIDATION-20261005.json. Previous video evidence remains in IPTV-HLS-VALIDATION-20261005.json.
- New AM9 HUD/track checks are pending: device wakes but HDMI puts it to sleep again (power service reports last sleep reason hdmi). No HDMI/CEC/device settings changed. Initial new-build Live TV screen rendered before sleep.
- AM9 controlled video/UI checks cover progressive TS, sliding HLS, byte ranges, extensionless format selection, failure handling, explicit activation, replacement, favourites/paging and background cleanup. No real provider stress or credential copying.
- Synthetic 640×360/25 FPS H.264/silent AAC evidence is not audible output, HDR/Atmos, UHD decoder capacity or broad provider/codec certification.
- Current policy allows one foreground IPTV decoder and one acquisition for unknown/shared account capacity. These are estimates, not measured device limits.
- Media redirects remain rejected. No DRM, encrypted HLS, fMP4 or separate-audio certification. No finished timeshift/recording/multiview yet.
- Expanded video retains the prototype toolbar. English fallback strings remain; source form focus with an open IME needs further device checks.
- Original dirty fork and device apps/accounts/preferences/recordings are preserved. Prototype package: com.nuvio.iptv.prototype. Nothing published.

## Change history

196dcf4 ingest/admission; 9122fc6 catalogue/M3U transport; b8e60e9 XMLTV persistence; 84924e3 guide HTTP/cache; 53bd036 bounded browse/mapping; 7283bf5 source forms/profile cleanup; 7ec290a governed live playback; e636438 Xtream setup/HTTP1.0 fix; a9871c7 durable stream format/HLS fallback fix.

## Upstream review

Reviewed draft NuvioMedia/NuvioTV PR #3788 at e10c639: reproduced a preview direction-reversal seek error and a live-label delay discrepancy; documented retention/recovery concerns. Not imported. See IPTV-UPSTREAM-PR3788-REVIEW.md. Forthcoming timeshift tests must distinguish RAM load targets, actual playable windows and local capture retention.

## Capture foundation — internal, not yet a playable feature

- Added an exclusively owned, bounded segment spool with atomic complete-segment/index publication, close/reopen recovery and scoped orphan cleanup.
- Pause/recording anchors and open readers prevent eviction of retained media. Capacity exhaustion reports backpressure; gaps and discontinuities remain separate in actual retained bounds.
- Seven focused filesystem tests passed on both JVM and AM9, in addition to the prior HUD/guide suite. See IPTV-CAPTURE-STORE-VALIDATION-20261005.json.
- Capture transport, seekable player integration, durable schedules/leases and internal/USB/SMB recording are still unfinished. File sync/atomic rename testing does not establish physical-power-loss durability. This store has not yet been included in the installed prototype APK.
