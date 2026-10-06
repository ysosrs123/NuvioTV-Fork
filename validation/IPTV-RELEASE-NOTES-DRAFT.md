# Nuvio IPTV — draft release notes

Unreleased. Branch `iptv/player-binding` (includes `main` as of 6 October 2026,
1.1.0-beta-nt4.2 / build 1461). IPTV is enabled only in the `iptvPrototype`
flavour (package `com.nuvio.iptv.prototype`, shown as "Nuvio IPTV Prototype", installs
alongside the normal app); the `full` flavour hides it. Nothing published.

## Live TV

- Live TV has its own entry in the main navigation (IPTV build only).
- Redesigned Live TV and Live TV sources screens in the app's own style: theme
  colours and typography, glass/frost panels when the V2 glass presentation is on,
  the V2 focus edge (or the classic focus ring), channel logos with initials as a
  fallback, loading placeholders and empty states with a next step (add a source,
  refresh, open categories).
- Adding a source starts with a choice of M3U playlist, Xtream account or Stalker
  portal, then a form with examples and hints (MAC address checked as you type);
  "Save and load channels" starts the first refresh. Each source and guide has
  options to watch, refresh, choose guides, edit, move up or remove (with
  confirmation). The automatic provider guide is labelled and cannot be edited.
- Multiple independent M3U, Xtream Codes and Stalker portal live sources with
  per-profile isolation. Server and username are visible while typing; the
  password has a Show/Hide toggle; a missing `http://` is added and spaces trimmed.
- Background refresh: sources and guides refresh one at a time outside the screens,
  show their stage ("Downloading channels…", "Saving channels…", "Updating programme
  guide…") and "Updated … ago", and refresh automatically when older than 12 hours
  (sources) or 6 hours (guides). Saving a source or guide starts its refresh.
- Clear refresh errors naming the failed step (address, network, server status,
  redirect, size, format or sign-in); a refresh that is not accepted says so on the
  source. Live TV sources shows each source's channel count.
- Live TV opens the first source that has channels; an empty source explains how to
  switch sources or refresh.
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
- Guide-first Live TV: programme panel and live preview above a channel guide in
  provider order with logos, programme times, progress on what is airing, a time bar
  with the current time and a now line; the focused row stays about a third of the
  way down while scrolling, and channel up/down page through the guide. Left/right move a shared time cursor
  across programmes; a category rail offers favourites, all channels, provider
  categories, source switching and source settings. OK previews a channel; OK again
  goes full screen without re-tuning. Long-press or Menu opens channel options.
- Full screen: up/down and channel keys zap (held keys do not repeat), a banner
  shows the channel logo, programme progress, minutes left, what is next and quality
  badges; OK opens the player's control deck, which follows the button layout,
  order, visibility and icon or labelled style chosen in the player settings
  (play/pause, start over, stats, audio, subtitles, aspect ratio, info and the More
  menu; actions that only apply to films and episodes are left out); "Reconnecting…"
  appears when a reconnect takes more than two seconds; right recalls the last channel, left
  opens a panel with recent channels, all channels and the focused channel's
  schedule, and number keys jump to a channel.
- Guide matching: channels without a guide ID match by a normalised name when exactly
  one guide channel matches; linked guides follow their linked order (Move up in
  Sources); a guide channel can be chosen from a searchable list per channel.
- Large catalogues save quickly: rows are sealed with a Keystore-wrapped data key,
  Xtream rows store no login (it is added from the encrypted connection when tuning),
  and channels are written in chunks of about 2,500 rows into an unpublished list
  that replaces the old one in a single final step, so Live TV keeps showing the
  previous list until the new one is complete. Save timings are logged.
- The provider's own Xtream guide downloads in the background, keeps only that
  provider's channels and allows large guides (512 MB, 15 minutes).
- Foreground playback of progressive MPEG-TS and HLS (including extensionless HLS
  when chosen), with a per-channel Auto/HLS/MPEG-TS choice. AC-3/E-AC-3 audio falls
  back to the bundled decoder on TVs without hardware support.
- Playback stats use the player's stats HUD: provider, server host, format, live or
  catch-up, video, HDR, bitrate, dropped frames, audio, buffer, live offset, speed,
  loaded data, open requests, tune time, rebuffers, state and memory, with the same
  quality dots. Only the host name is shown, never full addresses or credentials.
- Catch-up: channels with an archive show a catch-up mark; OK on a past programme
  plays it, "Watch from the start" restarts the current programme, left/right and
  the media keys skip 30 seconds, the end of a programme returns to live, and
  "Return to live" is in the channel options. Xtream archives use the timeshift
  address with the device's time zone as the provider time; M3U channels use their
  catch-up attributes.
- Channel logos from M3U tvg-logo, Xtream stream_icon and Stalker logo fields
  (http/https only).
- Guide ids with a feed suffix (for example abc.uk@SD) match the base guide channel.
- Channel search from the rail (folded, within the current category).
- Each source opens on the category, favourites or all channels last chosen for it.
  Holding OK (or Menu) on a category hides it from the rail into a "Hidden
  categories" section and its channels leave All channels (they still open from
  that section).
- Favourites keep their order: new ones go to the end and can be moved up or down
  from the channel options in the favourites view.
- Guides named in an M3U header (url-tvg, x-tvg-url) are added and linked when the
  playlist first loads (up to four; an address already added is reused) and refresh
  with it. The automatic Xtream guide and these guides can be unlinked or removed and
  stay that way.
- Xtream channels without guide programmes show now and next from the provider's
  short guide (one channel at a time, cached for five minutes).
- Large guides: the parse limit now matches the 512 MB download limit (the cause of
  the 145 MB provider guide failing as "unexpected format"); a DOCTYPE line, a byte
  order mark, leading spaces, UTF-16 and double gzip are accepted; failures name the
  reason (empty, web page, JSON, ZIP, not XML, not XMLTV). At most 200 programmes per
  channel and 50,000 channels are stored, and descriptions are cut to 400 characters.
- Audio/subtitle track dialog with Automatic, Off and per-player choices.
- Search ignores case and compatibility forms (for example ß/ss, final sigma).

## Ready below the UI (screens pending)

- Stalker Portal (MAC-based) sources with stream links created at tune time.
- Provider account groups with per-group stream limits.
- Guide import from a folder on internal storage or a USB drive for TVs without a
  file picker.

## Reliability

- A stream is replaced only after the previous decoder and HTTP connections have
  closed; an uncertain release keeps its reservation rather than over-committing.
- Live streams follow provider redirects (common for Xtream) and reconnect after
  network drops, provider-ended streams, falling behind the live window or a stall
  of more than 20 seconds (after 1, 2, 3 then 5 seconds, six attempts) before an
  error is shown. Decoder errors still fail at once. HLS keeps about six seconds
  behind live with small speed adjustments. HLS does not fall back to other
  renditions after a failure. HTTP/1.0 metadata servers no longer hit stale
  connections.
- Guide redirects are limited to six, never downgrade to HTTP, never carry userinfo,
  cookies or auth, and cache validators are not sent after a redirect.
- Oversized XMLTV comments, text or attributes are rejected before they are built in
  memory.
- Switching between Sources and Live no longer stacks screens; the periodic refresh
  keeps focus and programme info.
- Guide file permissions are released when no feed uses them.
- Refresh failures, guide import failures and Live TV load failures are logged
  (tag `NuvioIptv`) with the failing step and error type only, never addresses or
  credentials. Sources refresh before guides, and provider guides refresh after
  their source so they can be filtered to its channels.

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

## Fixes and improvements since the last device validation (b68985a)

Playback and capture
- Long captures now play to the end: played batches are released so the reader can
  load later segments (cause of the capture-player test timeouts).
- An explicit start at the beginning of a capture is honoured instead of jumping to
  the live edge.
- Segments whose video packets declare their length no longer lose their last frame.
- Buffered segments play out before an epoch boundary or stop is reported.
- Removed a start-up race that could fail playback with a stale-snapshot error;
  skipping late frames at the live edge no longer discards the rest of the picture
  group; all retained segments stay reachable.
- AC-3/E-AC-3 channels fall back to the bundled decoder on TVs without hardware
  support.

Ownership and reliability
- An account's stream slot can no longer stay "closing" until restart.
- Stopping a live HLS capture now confirms properly, retries a failed segment-body
  close, and keeps the real BACKPRESSURE/STORAGE_BLOCKED state.
- Later joiners start a capture that an interrupted first join left unstarted.

Sources and guides
- Playlists with rotating stream tokens keep refreshing (unused removed-channel
  records are pruned).
- Oversized guide comments, text or attributes are rejected before they use memory.
- A few malformed or unmatched programmes no longer reject a whole guide; duplicate
  channel entries merge their names.
- Search matches regardless of case and compatibility forms (ß/ss, final sigma).
- Guide URLs (which often carry credentials) are kept out of logs; cache headers
  are not sent after redirects; redirects follow up to six hops.
- Guide file permissions are released when no feed uses them.

Screens and app
- Sources and Live no longer stack on each other; the 30-second refresh keeps focus
  and programme info; profile clean-up and session opening run off the main thread.
- IPTV settings appear only in the prototype build (`FEATURE_IPTV_ENABLED`).
- Includes `main` as of 6 October 2026 (1.1.0-beta-nt4.2, build 1461).
- The debug build workflow can build the prototype variant on GitHub Actions.

## Known limitations

- One foreground IPTV decoder and one acquisition per account are allowed by
  default; these are estimates, not measured device limits.
- No DRM, encrypted HLS, fMP4 or separate-audio
  support has been validated.
- No timeshift, recording or multiview yet. Account groups have no UI yet. Stalker
  portals have no catch-up. Xtream catch-up assumes the provider uses the device's
  time zone.
- Subtitles, closed captions, DVB subtitles and teletext are not all validated.
- English fallback strings remain; on-screen keyboard focus in source forms needs
  device checks. Devices without a document picker need the folder import UI.

## Validation

- Last full device validation: commit `b68985a` (full app compile, 317 IPTV JVM tests,
  27 AM9 capture/codec fixtures).
- Since then: 276 core and 137 data-layer JVM tests pass on a host harness; the
  device tests compile. GitHub Actions builds the prototype APK with the IPTV JVM
  suites under Gradle passing, most recently at `105791e` (Live TV redesign, catch-up,
  control deck, stats HUD, short guide). Store, removal and catalogue-save tests that
  need real SQLite and Keystore run only on device. Device fixtures and AM9 use are
  pending. See IPTV-HANDOVER.md.

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
