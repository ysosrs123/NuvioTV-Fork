# Nuvio IPTV — draft release notes

Unreleased. Branch `iptv/player-binding` (includes `main` as of 6 October 2026,
1.1.0-beta-nt4.2 / build 1461). IPTV is enabled only in the `iptvPrototype`
flavour (package `com.nuvio.iptv.prototype`, shown as "Nuvio IPTV Prototype", installs
alongside the normal app); the `full` flavour hides it. Nothing published.

## Live TV

- Live TV has its own entry in the main navigation (IPTV build only); IPTV has no
  entry in the main Settings.
- Live TV settings, at the bottom of the Live TV menu, brings together sources and
  guides, phone or computer setup and recordings, plus: default stream format for
  channels on Auto; pause and rewind with catch-up (on by default); the stats overlay
  on open; which view Live TV opens on (last category, all channels, favourites or
  Sport); showing Sport in the menu; showing hidden categories again; multiview layout
  and picture quality; how early recordings start (default 1 minute) and how long they
  keep going after the end (default 2 minutes). These settings belong to the TV, not
  the profile.
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
- Channel search from the rail (folded, within the current category), and "Search
  what's on now", which finds channels whose current programme title matches (from
  guides imported with this version; older guide data becomes searchable after its
  next refresh, sources after theirs).
- Multiview: up to four channels at once from the channel options ("Add to
  multiview"); sound follows the focused picture, OK goes full screen on it, and each
  picture can be changed or removed. Each picture uses a connection on its account.
  The number of pictures follows the device (memory, hardware decoders and their
  reported decode capacity: one on low-memory devices, two on 2 GB boxes, up to four
  on 4 GB), and multiview is hidden where only one picture fits.
  Each picture asks for the quality matching its size on the TV's actual output, so
  a 4K TV gets 1080p per picture and a 1080p TV 540p, within what the box can decode
  (on Android 10 and later from the decoder's reported capacity; Android 9 uses a
  cautious single-stream check). If capacity is short the other pictures step down
  before the focused one. Two layouts: Grid and One large (one big picture with up to
  three small ones; Show large picks which). Picture quality can be Automatic,
  Sharpest or Lightest; it applies to channels that offer several qualities (most
  single-quality MPEG-TS channels play as sent), and a new picture is refused when
  the decoder has no room left.
- Recording: record the programme on now (or the channel), schedule a programme from
  the guide, stop or cancel from the channel options; the guide marks recordings, and
  Recordings (from the rail) lists scheduled, running and finished recordings and plays
  them with 30-second skips. Each recording uses its own connection on the account,
  waits up to 45 seconds for one to free up, starts a minute early and runs two minutes
  over unless that would overlap a neighbouring recording, and stops at six hours.
  MPEG-TS is copied as is; HLS appends TS segments (encrypted HLS and fMP4 are refused).
  Files stay on the device (app storage) and are removed with the profile. A recording
  needs room for about 2.5 GB per hour plus a 500 MB reserve. Scheduled recordings use
  exact alarms (granted at install on Android 13 and later), survive restarts and are
  re-armed after a reboot.
- Phone or computer setup: Live TV sources has "Set up from phone or computer", which
  shows a QR code, an address and a six-digit code. A phone or computer on the home
  network can add or edit playlists, Xtream accounts, Stalker portals and guides with a
  real keyboard; every change is shown on the TV and saved only when confirmed there.
  Saved passwords are never sent to the phone, and pointing an existing account at a
  different server needs its login typed again. The page works only on the home
  network, over plain HTTP, while the setup screen is open (it stops after 10 minutes
  without use).
- Each source has its own connection limit (1 to 4) under its options in Live TV
  sources; new sources no longer share one limit with other providers.
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
- Sport: a Sport entry in the Live TV rail lists channels with sport on now or in
  the next six hours, live matches first, in the usual guide (watch, record,
  catch-up). It works from the linked guides only, with no outside sports data:
  programmes count as sport from their guide categories (for example "Sports",
  "Football"), competition names (Premier League, AFL, NRL, NFL, NBA, Formula 1 and
  others) or fixture titles such as "A v B" on a sports channel or marked live;
  highlights, previews, news, magazines, talk shows, documentaries and films are left
  out. The list refreshes every five minutes while shown. After updating, guides
  refresh once on first opening Live TV so Sport can fill (provider guides follow
  their source's next refresh).
- Pause and rewind live TV: Play/Pause pauses a live channel ("Paused" is shown). On
  channels with catch-up, resuming after more than 20 seconds carries on from the
  minute it was paused, and Rewind steps back about a minute at a time, both through
  the provider's archive ("Behind live"; Return to live in the channel options). If
  the archive has nothing for that moment yet, playback returns to live and says so.
  Channels without catch-up resume where they were while the stream is still held,
  otherwise from live. A pause caused by the system (another app taking the sound,
  headphones unplugged) shows as paused too.
- Guide folder: Add guide can pick an XMLTV file from the iptv-guides folder on the
  device or a USB drive (listed with size and date), for TVs without a file picker.
- Removing a source also removes the guides no other source uses (such as guides
  named in its playlist header) and its remembered category and hidden categories;
  removing a profile clears its remembered categories too.
- Search ignores case and compatibility forms (for example ß/ss, final sigma).

## Ready below the UI (screens pending)

- Provider account groups shared by several sources (each source has its own
  connection limit in Live TV sources; grouping sources under one shared limit has
  no screen yet).

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

## Capture and timeshift (internal, disabled)

Built and host-tested, not exposed in the UI (recording, above, uses its own simpler
stream copier and does not depend on this):

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
preroll and seek acknowledgement, measured memory and storage margins.

## Fixes and improvements since the last device validation (b68985a)

Latest work list (7 October)
- A provider guide containing a channel entry without an ID is no longer rejected
  as a whole; that entry is skipped (found on the first device run of this build).
- Guides named in an M3U header are removed with their playlist when no other source
  uses them; Live TV's remembered category and hidden categories are removed with
  their source or profile (multiview settings stay, they are device-wide).
- The catalogue upgrade test from schema 2 now builds a real schema 2 database
  (it set version 2 on a current database before, so the upgrade re-added columns).
- A paused live picture no longer shows the "Connecting" spinner, and a reconnect no
  longer unpauses it.
- The PR Full Debug Build workflow can build a minified release APK
  (`build_type: release`) to check R8 with IPTV enabled.

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

Live TV, after the redesign (review fixes)
- Zapping, number entry, last channel, start over and the end of a catch-up
  programme no longer drop out of full screen.
- Returning from full screen keeps the guide position and focus; search results no
  longer take focus from the search field; the first OK after a long-press menu is
  no longer lost; hiding a category keeps focus in the rail.
- Back with the category rail open closes the rail instead of leaving Live TV.
- Skipping or reconnecting during catch-up no longer restarts the programme.
- Background reloads keep every loaded row (up to 1,000) instead of cutting to 200.
- Watch from a source's options opens that source; Sources actions are no longer
  ignored during a reload; removing a source or guide cancels its running refresh.
- The short guide is fetched again when nothing covers the current time; number entry
  reaches channels that are not loaded yet; removed or unlinked automatic and
  playlist guides stay removed; the track dialog marks Automatic when nothing is
  overridden.

Multiview and connections
- Every picture keeps playing; before, each picture took audio focus from the others,
  so only the last one played. Sound follows the focused picture.
- Adding, removing and leaving no longer race each other, and a picture that fails to
  open frees its connection.
- Connection limits are counted per profile and source account, so two profiles or
  two providers no longer share one limit.
- The number of pictures, background guide rows and buffer sizes follow the device
  (memory, low-RAM flag, hardware decoders and their reported decode capacity), for
  boxes from 2 GB sticks to 4 GB players on Android 9 to 14.

Recording
- Scheduled recordings use exact alarms on Android 13 and later (Android 14 no longer
  grants the older permission at install); where exact alarms are not allowed the app
  offers to open the system setting.
- Back-to-back programmes on one channel can both be scheduled; padding is trimmed
  instead of overlapping.
- Recordings wait for their start time and for a free connection; short recordings
  stop the service; the service always enters the foreground; record now confirms
  that it started; the list stays consistent when a save fails; a recording that
  cannot be played says so; recordings that will not fit on the device are refused
  with the space needed.

Phone setup (security review)
- Pointing an existing account at a different server needs its login (or MAC) again,
  so a saved login is never sent to another server; the TV shows the server change.
- Deeply nested or lenient requests are refused; slow or parallel connections are
  limited; the rate-limit table cannot fill up; only paired activity keeps the server
  running; changes from a stopped server are ignored.
- The TV confirmation starts on Reject, names the sending device and pauses after a
  rejection; the address shown skips VPN interfaces; finished drafts drop passwords.

## Known limitations

- Nothing after `b68985a` has been tested on a device. Device limits for multiview
  (pictures, decode capacity, what each box reports as its output height) come from
  what Android reports, not from measurements.
- No DRM, encrypted HLS, fMP4 or separate-audio support has been validated.
- Pause and rewind beyond what the player holds work only on channels with a
  provider archive and a guide programme, at one-minute steps (Xtream archive
  addresses take minutes). Without an archive a long pause may resume from live;
  there is no local time-shift buffer (the capture chain stays disabled).
- Sport is matched from guide text only: titles and categories in other languages,
  or guides without categories, can miss matches or include the odd non-sport
  programme. No reminders, team favourites or fixtures from a sports data service.
- Exact alarms in deep sleep on Fire OS and recording across stream gaps are
  unverified. With one connection on an account, watching and recording block each
  other. Stalker streams that need extra headers may not record. Encrypted HLS and
  fMP4 HLS cannot be recorded. Recordings are kept in app storage only (no USB/SMB).
- Stalker portals have no catch-up. Xtream catch-up assumes the provider uses the
  device's time zone. "Search what's on now" does not find programmes longer than
  24 hours.
- Subtitles, closed captions, DVB subtitles and teletext are not all validated.
- Live TV text is translated into all 40 app languages by the developer, not by
  native speakers; wording may need polishing.
- On-screen keyboard focus in source forms needs device checks. Devices without a
  document picker (such as the AM9) add guide files through the iptv-guides folder.
- Phone setup uses plain HTTP on the home network (pairing code, single-use token and
  confirmation on the TV); it is not meant for untrusted networks.
- The minified release build with IPTV is built in CI (see Validation) but has not
  been installed or smoke-tested on a device.

## Validation

- Last full device validation: commit `b68985a` (full app compile, 317 IPTV JVM tests,
  27 AM9 capture/codec fixtures).
- 7 October work list: 349 core and 152 data-layer JVM tests pass on the host
  harness and the device tests compile; GitHub Actions debug build green at `e4fd054`
  (run 37549887460: Sport, pause and rewind, removal fixes, guide folder). A fourth
  independent review covered this work; its 10 findings are fixed; debug build green
  at `f465328` (run 37552365804); with Live TV settings and all translations at
  `5276c31` (run 37555640125). The minified release build with IPTV enabled (R8)
  built cleanly at `d8d4a03` (run 37550880901) without new keep rules. New device tests (header-guide removal, Sport
  query, guide 4→5 and catalogue 2→8 upgrades) run only on a device.
- Before that: 340 core and 151 data-layer JVM tests pass on a host harness; the
  device tests compile. GitHub Actions builds the prototype APK with the IPTV JVM
  suites under Gradle passing, most recently at `73b59de` (run 37545837449; adds
  now-on search, multiview, recording, phone setup and multiview sizing). Store,
  removal and catalogue-save tests that need real SQLite and Keystore run only on
  device. Three independent code reviews (Live TV redesign; phone setup security;
  recording and multiview) were done and every finding fixed or answered (see
  IPTV-CODE-REVIEW-20261006.md). Device fixtures and AM9 use are pending. See
  IPTV-HANDOVER.md.

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
