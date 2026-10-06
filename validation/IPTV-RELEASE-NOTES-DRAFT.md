# Nuvio IPTV — draft release notes

Unreleased. Branch `iptv/player-binding` (includes `main` as of 6 October 2026,
1.1.0-beta-nt4.2 / build 1461). IPTV is enabled only in the `iptvPrototype`
flavour; the `full` flavour hides it. Nothing published.

## Live TV

- Multiple independent M3U and Xtream Codes live sources with Original/V2 source
  forms, masked credentials and per-profile isolation.
- Bounded playlist parsing and refresh, stable channel identities across refreshes,
  favourites, hidden channels, custom names, last-good catalogues and review before
  large shrinks. Unused identities of removed channels are pruned so token-rotating
  playlists keep refreshing.
- Encrypted local credentials and catalogue; profile deletion removes IPTV data.
- Xtream authentication, account active/expiry checks, categories and live channels.
  Refresh never fetches media, logos or advertised alternate hosts.
- Multiple XMLTV feeds (HTTP/HTTPS, gzip, or a local document), bounded parsing,
  cache validators, feed linking and manual channel mapping. A few malformed or
  unmatched programmes are skipped instead of rejecting the whole guide; duplicate
  channel entries merge their names.
- Live TV screen: preview and expanded video, 24-channel pages, source switching,
  favourites and now/next programme info. Focusing a channel does not open a stream.
- Foreground playback of progressive MPEG-TS and HLS (including extensionless HLS
  when chosen), with a per-channel Auto/HLS/MPEG-TS choice. AC-3/E-AC-3 audio falls
  back to the bundled decoder on TVs without hardware support.
- HUD with format, buffer, live offset, transfer rate, open requests, tune time,
  rebuffers and dropped frames. No URLs or credentials are shown.
- Audio/subtitle track dialog with Automatic, Off and per-player choices.
- Search ignores case and compatibility forms (for example ß/ss, final sigma).

## Ready below the UI (screens pending)

- Guide grid layout for a channels-by-time EPG.
- Automatic guide for Xtream sources from the provider's own XMLTV.
- Stalker Portal (MAC-based) sources with stream links created at tune time.
- Provider account groups with per-group stream limits, and source ordering.
- Guide import from a folder on internal storage or a USB drive for TVs without a
  file picker.

## Reliability

- A stream is replaced only after the previous decoder and HTTP connections have
  closed; an uncertain release keeps its reservation rather than over-committing.
- Failed loads do not retry automatically and HLS does not fall back to other
  renditions after a failure. HTTP/1.0 metadata servers no longer hit stale
  connections.
- Guide redirects are limited to six, never downgrade to HTTP, never carry userinfo,
  cookies or auth, and cache validators are not sent after a redirect.
- Oversized XMLTV comments, text or attributes are rejected before they are built in
  memory.
- Switching between Sources and Live no longer stacks screens; the periodic refresh
  keeps focus and programme info.
- Guide file permissions are released when no feed uses them.

## Capture, timeshift and recording (internal, disabled)

Built and host-tested, not yet exposed in the UI:

- Bounded local segment store with atomic publication, retention pins, backpressure
  and physical free-space fences.
- One shared acquisition per channel with independent viewer and recorder consumers,
  governed by device/provider admission.
- Bounded HLS capture source with strict HTTP ownership and no silent gaps.
- MPEG-TS inspection for a narrow AVC/AAC profile, hash-bound local extraction,
  transactional sample staging, stable PTS epochs and seek policy.
- Incremental reader, growing Media3 period and media source, and a video player
  binding. Played batches are released during playback so long captures reach EOS.

Still required before enabling: device validation of the player path, renderer
preroll and seek acknowledgement, measured memory and storage margins, durable
recording services and USB/SMB storage.

## Known limitations

- One foreground IPTV decoder and one acquisition per account are allowed by
  default; these are estimates, not measured device limits.
- Media redirects are rejected. No DRM, encrypted HLS, fMP4 or separate-audio
  support has been validated.
- No timeshift, recording, multiview or catch-up yet. Stalker Portal, automatic Xtream
  guides and account groups have no UI yet.
- Subtitles, closed captions, DVB subtitles and teletext are not all validated.
- English fallback strings remain; on-screen keyboard focus in source forms needs
  device checks. Devices without a document picker need the folder import UI.

## Validation

- Last full device validation: commit `b68985a` (full app compile, 317 IPTV JVM tests,
  27 AM9 capture/codec fixtures).
- Since then: 222 core and 117 data-layer JVM tests pass on a host harness; device
  fixtures and Compose UI compilation are pending. See IPTV-HANDOVER.md.

## Change history

196dcf4 ingest/admission; 9122fc6 catalogue/M3U; b8e60e9 XMLTV persistence; 84924e3
guide HTTP/cache; 53bd036 browse/mapping; 7283bf5 source forms/profile cleanup;
7ec290a governed live playback; e636438 Xtream; a9871c7 stream format/HLS fallback
fix; 0211656 HUD/document guides/redirects/tracks; 8f1316e capture spool; 4c3e0b0
shared capture; eaa43e5 HLS capture; 43b1dff TS inspection/extraction; 25395a2
retained inspection/epochs/seek; 4606418 staging/Media3 metadata; ae0c08b storage
fences; 6842df1 finite period; a960696 async reader; 0b82357 incremental loading;
34a4937 incremental reader; ed528c6 growing period; 6ad3799 media source; b68985a
ID3 track exclusion and AM9 capture validation; then the player binding, review
fixes, `main` merge and IPTV flavour gate on `iptv/player-binding`.

Upstream draft PR NuvioMedia/NuvioTV#3788 was reviewed (IPTV-UPSTREAM-PR3788-REVIEW.md);
nothing was imported.
