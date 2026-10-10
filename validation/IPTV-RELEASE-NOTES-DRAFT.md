# Nuvio IPTV — draft release notes

Unreleased. Branch `iptv/player-binding` (includes `main` as of 6 October 2026,
1.1.0-beta-nt4.2 / build 1461). IPTV is enabled only in the `iptvPrototype`
flavour (package `com.nuvio.iptv.prototype`, shown as "Nuvio IPTV Prototype", installs
alongside the normal app); the `full` flavour hides it. Nothing published.
Decided: IPTV will ship as part of the full app after a clean device pass, merged into
`main` as a single squash commit.

## Live TV

- Live TV is a destination in Nuvio's sidebar (IPTV build only). Left from the guide
  opens the Live TV menu and Left again the Nuvio sidebar; Back closes Live TV's own
  layers, then opens the sidebar, then exits as on Home. The sidebar and top bar stay
  hidden in full screen and multiview. Live TV settings are a "Live TV" category in
  the main Settings; the Live TV menu's Settings item opens it. (Replaces the earlier
  Exit item and separate Live TV settings screen.)
- After the first device pass: the corner picture plays the channel in focus after a
  short pause and opens full screen from Up then OK; HDR channels stay SDR in the
  corner picture; the menu has Exit Live TV, Recordings and Live TV settings at the
  top and Back leaves Live TV; category options on hold; multiview fills the screen;
  per-channel volume boost; Live TV appearance (colour theme, pure black, solid
  panels and plain background by default, optional artwork); automatic connection
  limit for Xtream; a failed channel change stays in full screen and says why;
  forms keep Save and Cancel reachable; the phone setup page is redesigned and can
  change Live TV settings.
- Live TV settings (now in the main Settings) bring together sources and
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
  re-armed after a reboot. While recording, the box is kept awake (CPU and Wi-Fi), so a
  recording carries on when the TV is switched off and the box goes to standby.
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

## Recording locations, guide, playback and setup (7 October 2026, not device-tested)

- Recording location: this device, a USB drive (thumb drive, HDD or SSD; FAT32, exFAT
  and NTFS; FAT32 recordings are split into 4 GB parts and play back as one; read-only
  drives are detected) or a network share (SMB 2 and 3, guest or account login).
  Network recordings go to a small local buffer first and upload as they record, so a
  short network drop does not lose the recording; uploads resume where they stopped.
  File names are made safe for every file system. SMB1-only servers are reported as
  such.
- Guide: compact, comfortable and large densities, programme titles that stay visible
  while scrolling, programme artwork where the guide has it, channel and category
  reordering, categories from all sources together, multiview across sources with more
  layouts, and picture-in-picture.
- Catch-up: scrub across programme boundaries; playback returns to live near the end.
  The Xtream catch-up address style is probed and remembered per source.
- Playback: per-channel headers from the playlist (user agent, referrer, other
  headers), endless reconnect with frozen-picture detection, a per-server format check,
  faster start on TS channels, and the display frame rate handed over as in the main
  player.
- Setup: assign guides to channels and manage profiles from the phone, copy the whole
  Live TV setup to another TV, encrypted backup and restore with a passphrase, an
  account groups screen, Stalker grouping by portal, and a review screen when a refresh
  would remove many channels.

## Added after wave 1 (7 October 2026, not device-tested)

- Recording targets: WebDAV (Nextcloud/ownCloud append as it records; other servers
  get one upload when the recording ends) and FTP/FTPS (explicit TLS), alongside SMB.
  Self-signed NAS certificates can be trusted from the test screen by fingerprint.
- "Movies folder" recording location on Android 10 and later: recordings are copied to
  Movies/Nuvio Recordings when they finish, so other apps can see them.
- Phone and tablet: the setup page lists finished recordings with Download and "Open
  in VLC"; links last 6 hours; the Live TV setup screen must stay open on the TV.
- Sport: optional fixtures from ESPN (unofficial) or TheSportsDB (your own key), off by
  default, with a league choice; fixtures are linked to channels by guide titles and
  broadcaster names and shown above the Sport grid.
- Streaming: seamless reconnect on TS channels (playback continues from the buffer while
  the connection is re-made); "Start playback after" (Fast / Normal / Safe) and "Safety
  buffer" (Off to 60 s, built by playing at 97% speed with pitch kept; HLS starts
  further back instead); HLS keeps the playlist's hold-back; Wi-Fi performance lock while
  watching; larger network buffer; a user agent per source; stats overlay shows buffer,
  delay behind live, speed, protocol, whether HTTP/3 is offered and reconnects; the
  fast-forward key returns to live.
- Experimental local timeshift (off by default): pause and rewind up to 15, 30 or 60
  minutes on TS channels, buffered to internal storage or a USB drive and deleted when
  you change channel or stop.
- IPTV movies and series: Xtream and M3U movies and series are imported into their own
  catalogue (M3U movie/series entries no longer appear as live channels). With
  "IPTV movies and series" on (Settings → Playback → Stream selection), matching titles
  appear as a stream source on Nuvio's movie and episode pages; continue watching and
  Trakt/Simkl/MDBList work as for other streams, and provider addresses with account
  details are never stored. Movies and Series also appear in the Live TV menu with a
  poster grid, categories and search; artwork can come from the provider or from Nuvio
  (TMDB). Titles that match open Nuvio's own page; others open a Live TV page with play
  and resume.

- Home screen: optional Live TV rows (favourites with what's on, sport on now,
  recently added movies and series, recordings), each switchable in Settings → Live TV.
- Movies and series: a playing IPTV movie now counts towards the account's connection
  limit; busy or refusing providers show a clear message straight away; the external
  player option is hidden for them; failover can use them.
- Live TV full screen: a "Return to live" button appears while you are behind live.

## Changes after the device passes of 8 October 2026 (not yet device-tested)

Guide (EPG):
- Guides store programmes only for channels in your sources, so large provider guides now
  fit; the storage limit follows free space (256 MB up to 2 GB) with a share per guide.
- New "Guide days" setting: 1 day back and 3 ahead by default; up to 7 back for catch-up
  and 7 ahead.
- Guides load two at a time, import in large batches and download to a temporary file
  first, so they are much faster.
- More XMLTV files are accepted (timestamp variants, channel ids differing in case or
  spaces, programmes without a channel entry); a refused file says why. Dropbox and other
  links that redirect with an empty "#" now work.
- A guide you add is linked to the source automatically; each channel uses the first
  linked guide that has programmes for it. Upgrading clears guide data once; guides
  re-import when Live TV opens.

Playback:
- Channel changes no longer fail with "The previous player has not confirmed it stopped"
  (closing a player could fail on secure streams and after a slow 4K decoder release).
- Faster channel starts: shared, kept-alive connections, fewer database round trips, the
  old player no longer blocks, and the stream host is pre-connected after you rest on a
  channel (no stream request is made, so it does not use a provider connection).
- Nothing plays while you scroll the guide; OK plays the channel in the corner, OK again
  goes full screen.
- 4K and HDR channels show the channel logo in the corner window (sound continues) instead
  of a stuttering picture; full screen is unchanged.
- "Behind live" now counts only delay added while watching; when behind live, Right opens
  the controls on Return to live.
- In full screen, Left closes the channel list again.

Live TV screens:
- Live TV's menu item is "Settings" and opens Settings at the Live TV category.
- Wider channel column with two-line names and less empty space; menus, guide, dialogs
  and Movies/Series in the Nuvio V2 style; panels and dialogs are no longer see-through;
  menu and guide share fonts and colours.
- Colour themes come from the app's own list, including unlocked supporter themes, and
  now apply; Solid panels is off by default.
- Recording asks where to save (this device, a USB drive, the Movies folder or the
  network location); recording names drop decorative characters (such as "ᴸᶦᵛᵉ").
- Back and Left leave Live TV Sources; Back leaves Sport; smoother switching between Live
  TV and Settings.
- Shared connections and Copy and back up explain what they are for.

Sport:
- Fixtures view with a featured game (league, round, venue, score by period, clock, NFL
  situation and last play, win chance), rows for Live now, Close games, Later today,
  Tomorrow, later days and Finished, team logos, favourite teams and a "Show scores"
  switch for spoiler-free viewing.
- Fixtures load when Live TV opens and show from the cache straight away; channel
  matching runs separately. Score parsing checked against real ESPN and TheSportsDB
  responses (NRL and cricket scores corrected).

## Changes after 8 October 2026 (not device-tested)

Sport:
- More leagues to choose from: ATP and WTA tennis, PGA Tour golf, NASCAR Cup and the
  United Rugby Championship. Tennis matches show games per set, seeds and who is
  serving; golf shows the leaderboard; F1 and NASCAR weekends list their sessions; UFC
  events list their bouts with the main card and prelims; cricket shows the chase;
  baseball shows inning, outs, count and runners. These formats are built from
  documented ESPN shapes and are not yet checked against live responses.
- New Sport settings: "While watching" (score overlay style, Glance by default; which
  games: followed teams and close games by default; alerts held 45 s so they do not
  beat the picture; skip the game on screen; which changes alert: start, scores, full
  time) and "Across Nuvio" (pop-up, chip or off while watching films and series; quiet
  in the last minutes; reminder lead time; hide results of recorded games). The screens
  that use these settings follow in the next changes.
- Fixtures are now fetched by one shared service for the whole app. Leagues with a
  followed team's live game refresh every 30 s instead of every 2 min.
- Guide: matched games show a league badge, a live score and clock, game progress, an
  amber edge for close games and "Reminder set"; studio shows get a muted badge. A
  "Games now" lane above the guide lists live and soon-starting games (OK watches). A
  "Sport only" filter shows only channels with a matched game.
- Score overlays in full screen: Glance (appears only when a followed or close game
  changes, OK switches), score bug, stacked cards or ticker; the game on screen is
  skipped; per-sport formats (football, AFL, NRL, NFL, basketball, baseball bases and
  count, ice hockey, cricket chase, tennis sets and server, golf leader, F1 session, UFC
  round). Reminders pop up in Live TV.
- Player: game banner with score and how far behind live; Game Centre (Info key or the
  control deck) shrinks the picture and shows key moments (watch each one through
  catch-up), team stats, line-ups, table and head to head, other games; score markers
  on the catch-up and timeshift bar with Previous and Next score; Other feeds lists every
  channel showing the game and can switch to a backup when the stream stalls.
- Multiview can show a game screen (score, situation, win probability, last plays) or an
  all-scores screen in a tile; these use no decoder.
- Sport section: cards fitted to each sport, hero with Follow and Remind me, a "Tonight"
  timeline with a lane per sport, an All scores screen, Remind me in the options, and
  results of recorded games hidden until you choose to show them.
- Across Nuvio (IPTV build): Home shows "Live sport" and "Your teams" rows; a team page
  (from Your teams) with the next games, Follow, Remind me and "Record every game";
  while a film or series plays, a followed team's start, scores and full time appear as
  a pop-up (Watch the game saves your place and opens Live TV) or a one-line chip, never
  in the last minutes of a film; "While you were watching" when it ends. Scores are
  checked in the background only for followed teams' live games while Nuvio is open.
- Recordings remember when they were first played; sport recordings show "Not watched
  yet" and keep their result hidden until played.
- Guide matching for events without two teams (tennis, golf, motor racing, UFC) uses the
  event or tournament name.

- The new sport screens and settings are translated into all 40 languages.

- Football score bugs show red cards and NRL shows the latest try, from the scoreboard's
  event list; the Sport hero and score markers use these events when game details are
  not loaded.
- Catch-up of a game that finished in the last 12 hours keeps its game banner and score
  markers.
- Other feeds also lists the same channel on your other sources; the backup switch
  prefers it.

After the device pass of 9 October:
- Back: full screen → guide → Live TV menu → "Leave Live TV?" (Stay / Leave to Home), so
  Live TV no longer closes by accident. Search, multiview, panels and dialogs close first.
- Full screen: Left opens the channel list, a second Left shows the categories (choosing
  one reloads the list), Left again or Back closes.
- Left on Live TV Sources returns to the guide.
- Guide: slightly larger programme titles; Compact is the default density (a saved choice
  is kept); the corner picture is larger (about 640×360 at 1080p) with at least six
  compact rows still visible.
- Panels and dialogs that were see-through are now opaque like the rest of Live TV: Live
  TV settings dialogs (incl. sport key and add league), the search field, the move hint
  and classic-style dialogs inside Live TV. Defaults unchanged (Solid panels, Pure black
  and artwork off).
- IPTV movies and series always open: Nuvio's detail page is used only when an installed
  addon actually returns details for the title (IMDb id first, then TMDB); otherwise —
  no addons, not signed in, no TMDB key, no ids, or a failed load — the IPTV title page
  opens with the provider's own plot, cast, director, genre, rating, artwork, runtime and
  year. Fixes "None of them provided metadata for id=tmdb:…". Home rows use the same rule.
- Left on the Movies and Series pages goes back to Live TV with the menu open.
- Channel switching: the old stream starts closing the moment OK is pressed; a request
  answered just as it was cancelled is now closed instead of keeping the old player
  "busy" for good; connections already cut no longer count; the switch waits up to 8 s
  with the normal loading state (instead of 1 + 3 s) and goes ahead earlier when the old
  decoder is free and the provider allows more than one connection; a second OK on the
  channel being tuned opens full screen instead of restarting it; stale messages from an
  earlier switch no longer appear. "Previous player has not confirmed it stopped" should
  now be rare; new log lines (`live close started`, `live close ms=… dns= connect=
  connected= cut=`, `live close unconfirmed reason=`) show the cause if it remains.
- Recording names and titles drop decorative lettering in every recording path: small
  capitals, superscript and modifier letters (for example "ᴸᶦᵛᵉ" becomes "Live"),
  fullwidth, mathematical, circled and squared letters become plain letters; flags, emoji
  and symbols such as ★ ⚽ ● ▶ | are removed. Existing recordings display cleanly too.
- Sport channel matching now covers the same days as the guide ("Guide days ahead" + 1,
  up to 8), looks at every hour in that range, retries busy hours with a larger limit and
  re-runs when a guide finishes refreshing. Before, a game the next evening could miss
  out ("No channel found") when many leagues were chosen.
- New Sport setting "Find channels using": Guide titles, Broadcaster lists (ESPN and
  TheSportsDB channel names) or Both (default). Guide matches come first; each channel
  says "In your guide" or "Broadcaster". Better for viewers outside the US.
- Sport fixtures beyond three days ahead refresh every 3 hours instead of every 30 minutes;
  the Guide days setting applies in the background too; "Record when a channel is found"
  is checked every minute while Nuvio is open (not only in Live TV) and follows kick-off
  changes; guide matching ignores programmes that started over 3 hours earlier, so busy
  hours are not crowded out.
- Match cards: Record (with the usual start/end padding) or Cancel recording; when no
  channel is known yet, "Record when a channel is found" (also offered in the "No channel
  found" dialog).
- Widgets in the space between the programme details and the corner picture: 1, 2 or 3
  tiles (default 2) sized to the free width (tiles too narrow to read are hidden). Hold
  OK (or OK) on a tile to choose: World clocks (1–4 of 101 built-in cities, local time,
  "+1 day", day or night; no internet needed), Sport strip (followed and close games,
  hidden scores respected), Up next on this channel, Recordings (now and next), Stream
  info (resolution, HDR, codec, bitrate) or Empty. Defaults: clocks for your city, London
  and New York, and Up next. Up from the guide reaches the tiles; Down returns.
- "Set up from your phone or computer" is now in the Live TV menu, on Live TV Sources, in
  the no-sources screen and in Live TV settings.
- This round's new screens and settings are translated into all 40 languages.

Sports data from ESPN and TheSportsDB combined (9 October):
- No more choice of data source: each league uses ESPN when ESPN has it (live detail,
  Game Centre, markers) and TheSportsDB otherwise, with your own TheSportsDB key. If ESPN
  fails for a league that TheSportsDB also covers, that league switches to TheSportsDB
  until ESPN works again. Sport settings: an on/off switch, one league list (leagues
  needing the key are marked), "Add a league" to search any TheSportsDB league, an
  optional key, "Team logos" (ESPN only, all, or monograms only) and the credit "Sports
  data from ESPN and TheSportsDB".
- With a key: live scores for TheSportsDB's football, basketball, ice hockey and
  baseball leagues, and TV channel names for followed teams' games (preferring your
  country's channels) to improve channel matching.
- Existing settings carry over (Sport off stays off; chosen leagues, key and followed
  teams kept). Teams followed under a slightly different name (for example "Richmond"
  and "Richmond Tigers") still match.
- Not checked against real TheSportsDB live score, TV channel or league list responses.

Checked against real ESPN responses (8 October evening, no game in play):
- Tennis: each tour shows only its own draws; matches with no time yet or no players
  are left out; short names and walkovers display correctly.
- Golf: holes played ("thru") and today's score from the round's hole scores.
- Cricket: the fielding side no longer shows a 0/0 copy of the innings.
- Game Centre: AFL goals and behinds, rugby conversions, penalty and drop goals appear
  as key moments; NRL half scores correct; rugby line-ups split starters and bench;
  rugby and AFL team stats; table points now found, so "If it ends now" works.

Fixes from a review of the sports build:
- Score markers and Previous/Next score are hidden when scores are hidden (Show scores
  off or an unwatched recording of the game), as everywhere else.
- Pop-ups in Nuvio's player respect hidden scores.
- Reminders always show (also outside full screen and with alerts off) instead of being
  dropped; Record and Watch on a team page work for teams you do not follow.
- Game details and team pages stop checking scores when Nuvio goes to the background.
- A score card no longer takes focus from the open channel list or number entry.
- Smoother playback while a pop-up is shown; sport checks resume after Sport is turned
  off and on again.

Fixes from an independent review:
- Recordings saved to the Movies folder no longer stop playing after a quick seek (a
  cancelled read closed the file for every later read; it now reopens).
- Seeking or Return to live during local timeshift no longer ends the timeshift session
  (reads use their own file handle, so a cancelled read cannot close the recording ring).
- Guide downloads stop early with "not enough space" when under 128 MB is free, instead
  of failing as "could not be accepted".

## Changes after the second 9 October 2026 device pass (not device-tested)

Phone and computer setup:
- After you add a source from your phone or computer, the setup page stays open, shows
  what was saved and lets you add an XMLTV guide for that source, add more sources or keep
  changing settings. A new guide can be assigned to a source as you add it ("Use it for").

Guide and widgets:
- The guide's top area is cleaner: no title, clock or update text; a small spinner beside
  the guide heading shows a guide update. The description gets three lines.
- The guide keeps showing the current programme for channels with now/next information
  during a guide update.
- Stream info widget: quality badges (4K, HDR10/HLG/Dolby Vision, frame rate, Atmos, 5.1),
  resolution, video and audio details, format, buffer health and dropped frames.
- Widgets have eight layouts (one tall, one square, one wide, two short stacked, two tall,
  tall plus two short, one large and two small squares, four small squares); every option
  works, and an older "3" setting becomes tall plus two short.
- Holding Up in the guide scrolls row by row to the top before going to the widgets.
- The Live TV menu opens with focus on Search channels; "Leave Live TV?" offers Leave to
  Home first.

Playback:
- 4K and HDR channels show their picture in the guide's corner (the corner now uses the
  same video surface as full screen; the TV may switch to HDR while browsing and the
  video's corners are square). Streaming setting "4K and HDR in the corner picture" brings
  back the logo if a box struggles.
- Going back from full screen to the guide no longer makes the corner picture and guide
  stutter (the corner no longer redraws the whole screen for every video frame).
- In full screen only Up/Down and the channel keys change channel; Right opens the
  controls (it used to recall the last channel; the Last channel key still does).
- Quiet AAC channels play louder: the decoder's target loudness is set to Android's normal
  level, and "Lift quiet surround audio" (on by default) adds 4 dB to 5.1 AAC mixed down
  to stereo. The stats show the AAC profile, channels, audio bitrate and gain; "Tune" is
  now "Start time" (time to the first picture).

Sources and guides:
- Live TV Sources: "Refresh all sources" and "Refresh all guides" with progress and a
  failure count. Sources on different accounts refresh side by side (at most two at once),
  sources on the same account one at a time; provider guides wait for their account too.
- Guides import faster (programmes for channels you don't have are skipped while reading;
  about 30% less import work on a large test guide) and the guide no longer waits behind
  an import's writes while it reads.

Sport:
- Section headings, focused card borders and the timeline are no longer cut off; each lane
  shows its own day heading; finished games leave the timeline.
- Sport fixtures and scores are on by default unless you turned them off (this also turns
  on the Home sport row for those users).
- Upcoming matches show large team logos and names, the date, a kick-off countdown and the
  broadcasters; season records read like "4W 0D 1L" (football W-D-L, NHL W-L-OTL, NFL
  W-L(-T), basketball, baseball and AFL W-L).
- Golf scores line up in columns and the golf hero shows the top 10 with Today and Thru.
- Dark league logos (e.g. the Premier League's) get a light backing so they show on black.
- TheSportsDB key: the Show button lines up with the field; Live TV settings dialogs opened
  from the main Settings are opaque.

Multiview:
- Add to multiview from full screen keeps the channel playing in the first picture without
  a restart (when it is playing live; otherwise it reopens as before).
- The multiview channel list offers all channels and categories (press Left), including
  when multiview was opened from Sport.
- Sound follows the focused picture reliably, including AC-3/E-AC-3 passthrough to a
  receiver (other pictures have their audio turned off, not just muted).
- Multiview explains why a channel can't be added (connections in use, decoder, memory,
  layout full, already shown) instead of doing nothing; the decoder budget now also counts
  4K and high frame rate capability, so boxes that can decode more get more pictures.

Movies, series and recordings:
- IPTV movies and series open on Nuvio's full detail page, using addon, TMDB (movies) or the
  provider's own information (plot, artwork, cast, seasons and episodes); Play starts the
  IPTV stream and resume stays local. The IPTV title page remains only as a last fallback.
  Library and watched marks for provider-only titles stay on this TV (not synced).
- Recordings play in Nuvio's normal player (seek bar, skip, audio and subtitle tracks,
  stats), including recordings on USB or a network share, through a local stream on the TV.

Guide:
- A background guide reload keeps each channel's programmes until the new ones arrive, so
  the grid no longer shows "Programme information unavailable" during a guide update.

Sport data (checked against a real capture with NHL and golf live, 10 October):
- Tennis doubles match tiebreaks show the tiebreak score; NHL goal markers sit at the right
  time, the power play line names the right team and is hidden at even strength; NFL
  scoring plays and recent plays appear in Game Centre; finished F1 sessions carry the top
  three; golf rows give clean columns including tee times; league logos prefer the
  variant made for dark backgrounds.
- Sport has a new look: the top panel is a scoreboard-style ledger (light top rule, city
  over nickname beside a large score, period table, clock and a one-sentence last play
  and win chance, Watch / Other feed / Follow on the right); score cards show two
  team-colour bands with logos, codes and right-aligned scores. Golf, tennis, racing and
  cricket use the same language.
- The Home sport rows and the guide's Games now strip use the same team-colour cards.
- Game Centre, team page, the pop-up over Nuvio's player, All scores, multiview score tiles
  and widgets use the same score bands and plain status text (no red dots or LIVE pills);
  the full-screen banner's score line follows the ledger style.
- NHL games show each goalie's saves and save percentage (Game Centre and the top panel;
  live hockey games now load the game summary for the focused game); racing cards show
  each finished session's winner (hidden when scores are hidden).
- Sport can follow NBL, WNBA and College Football (off by default); NBL and WNBA timelines
  use 10-minute quarters.

## Changes after the 10 October 2026 device pass (not device-tested)

Recordings:
- Recording file names use a short date and no code at the end, e.g.
  "Al Jazeera - 101 East - 10-Oct-26 1355.ts"; when a name is already taken, " (2)", " (3)"
  and so on is added. Existing recordings keep their names.

Live TV settings:
- Live TV settings open inside Live TV in its own look (Sources-style layout with links to
  Sources, phone setup and Recordings); Back returns to the Live TV menu. They are no longer
  a category in Nuvio's main Settings (reverses the 7 October decision).

Multiview:
- Before refusing another picture, multiview lowers the other pictures' quality where they
  offer lighter versions; the box's capacity is judged from both H.264 and HEVC decoders,
  their declared performance and the number of decoders. When a picture still can't be
  added, the message says what to do. Log lines `multiview device` and `multiview budget=`.

Guide:
- Stream info widget fills its space: two labelled columns in the short size (video,
  audio, bitrate, buffer), more detail in taller sizes, values only in tiny tiles.
- Guide info panel: the description uses all the height down to the hint line; long titles
  wrap to two lines in slightly smaller type.
- Left from the guide always moves focus into the Live TV menu (the guide cannot take focus
  while the menu is open; Back or Left closes the menu if focus ever misses); Back from Live
  TV settings lands on the menu's Settings item.
- Sport sits next to Movies and Series in the Live TV menu.

Sport:
- The Sport page guide covers the next 24 hours (about 28 h window); Sport only keeps every
  channel with a game in that time, including channels found only by broadcaster name, plus
  channels you chose to always include. The Sport panels cannot take focus while the Live
  TV menu is open.
- Sport settings → Sport channels: "Always in Sport only" and "Never in Sport only"
  (categories or channels); "Preferred for games" ranks chosen categories or channels first
  for Watch on, other feeds and recordings; "Never offer for games" removes them. A picker
  lists categories from all sources and searches channels.

Phone and computer setup:
- "Keep phone access on" (setup screen, off by default): pair a phone once with the code on
  the TV; afterwards it reaches setup and recordings at a fixed address whenever Nuvio is open
  or in the background on the TV, without the setup screen. Paired phones are listed on the
  TV (Remove, Remove all); the phone page has "Forget this device". Changes from a phone still
  need your OK on the TV, which now asks wherever you are in Nuvio (requests expire after 10
  minutes). Phones hold a random token in an HttpOnly cookie; the TV stores only its hash.
- Downloaded recording names use the same short date, e.g. "News at Six 07-Oct-26 1910.ts".

## Playback options, menu and source expiry (10 October 2026, not device-tested)

Sources:
- Xtream sources show the account expiry and the provider's connection count ("Expires
  12-Mar-27 · 2 connections", or "No expiry"); from 7 days before it becomes a warning
  ("Expires in 3 days", "Expired 2 days ago"). Stalker portals show their expiry when the
  portal reports a billing date; M3U playlists have neither. Stored in Live TV preferences
  (`provider-expiry`), no schema change.
- The Live TV menu shows a one-line warning under its title when a source is about to
  expire or has expired.

Live TV menu and channels:
- Hold OK on a menu item to move or hide it; Search channels, Settings, the categories
  block and Sources always stay (they can move); hidden items are listed dimmed at the
  bottom; "Reset menu" restores the default order.
- Channel options: "Open in external player" stops Live TV and plays the channel in another
  app through Nuvio's external player handling, with the channel's headers and user agent.

Playback and audio (new group in Live TV settings):
- Frame rate and resolution matching follow Nuvio's player settings ("Same as Nuvio",
  showing Nuvio's value) or can be set for Live TV only.
- Audio passthrough follows Nuvio's passthrough settings (route detection, formats,
  downmix, AC-3); "Off" decodes all sound in the app. Multiview pictures and the corner
  picture always decode in the app. Volume boost and the quiet-audio lift apply only to
  decoded sound.
- Tunnelled playback (off by default): main picture only, with the Automatic decoder and
  "4K and HDR in the corner picture" on; volume boost and the lift are off while it is in
  use; a tunnelled channel reopens rather than moving into multiview.
- Prefer surround audio (5.1) with stereo fallback, and Prefer audio language; a track you
  pick by hand still wins.
- Audio decoder: Automatic or Prefer app decoder (video stays on the hardware decoder).

Navigation fixes:
- Recordings: Back or Left returns to Live TV with the menu open on Recordings, or to
  wherever Recordings was opened from (Home, Live TV settings).
- Multiview: moving between categories and channels in the add/replace list no longer jumps
  to the pictures behind it.

Sport data (second 10 October capture: live NBL and live tennis):
- Rugby union game details no longer show empty extra score columns.
- Live NBL games and tennis matches with live sets, serve and tiebreaks are checked against
  real data.

## Changes after the evening 10 October 2026 device pass (not device-tested)

- Live TV settings: changing Pure black or the colour theme keeps focus on the setting you
  changed (the theme no longer rebuilds the screen).
- Multiview: the selected picture keeps its size and shows a thin border instead of
  enlarging over the others.
- The main guide always shows all channels; the Sport only / All channels switch is on the
  Sport page only. On the Sport page, Down from the cards reaches the switch and Up returns
  to the last card.
- Returning to Live TV from any of its screens or panels puts you back where you were: the
  same menu item, or the same channel, programme and scroll position. Closing channel,
  category or menu options, search or the guide picker returns focus to what opened it.
- A channel keeps playing (sound only) while you are in Live TV settings, Recordings,
  Sources or phone setup, and the picture comes back on return without re-tuning; it stops
  when you leave Live TV, the app goes to the background or a recording starts playing.

TheSportsDB (checked against the user's real captures, 10 October):
- TheSportsDB live scores now load (the sport names in the live-score requests were wrong);
  they exist only for soccer, basketball, ice hockey, baseball and American football.
- Finished NFL games and postponed basketball games no longer show as live; stoppage time
  shows as 45+1'.
- "Add a league" no longer lists placeholder leagues; TV channels match viewers in the
  Netherlands, Czechia and Turkey; built-in leagues carry their TheSportsDB ids (Big Bash,
  Super Rugby, A-League Women, PGA Tour, NASCAR Cup, URC).

Sport data (live Premier League and NBL capture, evening of 10 October):
- Football line-ups no longer label bench players "SUB"; leaders show plain numbers; cards
  shown to staff no longer appear as player cards on the match strip.
- Basketball play text no longer breaks across lines; NBL key-moment markers sit closer to
  the real broadcast time. TheSportsDB fixtures skip postponed games.
- Game Centre line-ups show each side's formation and mark booked (yellow) and sent-off (red)
  players; the cards are hidden while scores are hidden.

Fixes from the night review of 10 October:
- Phone access: a paired phone can't open a PIN-protected profile in the moments after the
  app starts; two phones pairing at once are both remembered; phone requests wait until you
  leave profile management or the profile intro; the network check and settings reads no
  longer run on the main thread.
- Playback: volume boost, surround lift and moving the channel into multiview now work when
  tunnelling is switched on but the device doesn't actually use it; your Nuvio passthrough
  settings apply from the first channel you open, without a pause when tuning; with
  tunnelling on, if the picture doesn't come back after Live TV settings or recordings, the
  channel re-tunes itself; multiview no longer overestimates how many HEVC channels the
  device can decode; the Live TV menu and display matching no longer read settings while
  the screen is drawing; pressing up or down at the edge of the sport cards no longer gets
  stuck.
- Sport: Watch on and other feeds prefer a channel whose guide lists both teams, even over a
  preferred channel matched only by broadcaster or league; channels picked for "Always in
  Sport only" always appear, even after a rename or alongside large categories; Sport only
  lists sport channels for the full next 24 hours on large guides; category picks work
  across sources whatever the letter case; channel matching runs far fewer searches, so
  Sport loads faster; Stalker expiry dates use the portal's time zone.
- Recordings never overwrite or delete an existing file with the same name on a network
  share or in the Movies folder; the new recording is saved as " (2)", " (3)" and so on.
  Deleting a recording only removes that recording's own file.
- Sport only now lists up to 200 channels (was 120). Moving focus from the guide to the
  sport toggle or Go live only counts as moved when focus actually lands there.
- Game Centre booked and sent-off marks are translated.
- Recordings to a network share start on time even when the share is slow to answer (the
  folder is checked just before the start); a WebDAV upload that is retried no longer
  leaves a full-size partial file behind; deleting a recording at the moment its upload
  finishes no longer leaves the file on the share.

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
  error is shown. Decoder errors still fail at once. HLS keeps the playlist's hold-back
  (at least three segments) with small speed adjustments. HLS does not fall back to other
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

- Device testing so far: the user's passes on 8 October (builds `f29cbe2`, `24d6868`), 9 October
  (`374e1f6`) and a further pass on `8354d89` (findings being worked on). Everything else,
  including the latest fixes, is host-tested and CI-built only. Device limits for multiview
  (pictures, decode capacity, what each box reports as its output height) come from
  what Android reports, not from measurements.
- No DRM, encrypted HLS, fMP4 or separate-audio support has been validated.
- Pause and rewind beyond what the player holds work only on channels with a
  provider archive and a guide programme, at one-minute steps (Xtream archive
  addresses take minutes). Without an archive a long pause may resume from live
  unless experimental local timeshift is on (TS channels, full screen only).
- Sport is matched from guide text only: titles and categories in other languages,
  or guides without categories, can miss matches or include the odd non-sport
  programme (with fixtures on, guide titles are matched to fixtures and "Find channels
  using" can add broadcaster lists).
- Exact alarms in deep sleep on Fire OS and recording across stream gaps are
  unverified. With one connection on an account, watching and recording block each
  other. Stalker streams that need extra headers may not record. Encrypted HLS and
  fMP4 HLS cannot be recorded. USB, SMB, WebDAV, FTP and Movies folder
  recording is untested on devices. WebDAV servers without append support keep the
  whole recording on the box until it ends.
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
- IPTV movies and series as a Nuvio stream source: playback, connection counting and
  the busy/refused messages are untested on devices; m3u8 movies are untested. Resume for titles without a TMDB/IMDb match
  stays on the TV and is not sent to Trakt, Simkl or MDBList.
- Sports data: ESPN's feed is unofficial and may change without notice. TheSportsDB leagues
  need the viewer's own key (the free test key is not allowed in published apps); its
  live scores, TV channels and league list are built from documentation and not yet
  checked against real responses. ESPN shapes were checked against real responses with no
  game in play: running clocks, NFL down and distance, baseball count and bases, tennis
  serve and in-play sets, golf mid-round, F1 results and UFC live rounds are unverified.
  Team logos and league badges are third-party trademarks; TheSportsDB artwork without a
  confirmed licence must not be shown in public builds (Team logos setting: ESPN only by
  default). Credit: "Sports data from ESPN and TheSportsDB".
- Sports alerts arrive a little after the picture by design (held 45 s by default); scores
  refresh every 30 s for followed teams' live games and every 2 min otherwise. Background
  checks and "Record when a channel is found" run only while Nuvio is open.
- Game Centre, score markers and win probability are ESPN-only; before kick-off NHL and MLB
  team stats show season totals. Score markers are exact only where the feed has a wall
  clock (NHL, MLB); elsewhere they are estimates.
- Widgets: text fit on small tiles and the Up path into the widgets need device checks;
  world clock city names are English.
- Channel switching still releases and recreates the decoder each time; a decoder that
  never finishes releasing still shows a message after 8 s.
- CI builds are made without the Nuvio sign-in server details and TMDB/Trakt keys (the
  fork has no LOCAL_PROPERTIES_BASE64 secret), so QR sign-in and those features are off in
  CI APKs. Streams use HTTP/1.1 or HTTP/2 only (no HTTP/3).
- Pre-connecting to the stream host does not open TLS in advance.
- The safety buffer relies on playing at 97% speed; with AC-3/E-AC-3 passthrough to a
  receiver the speed change may be ignored, so the buffer would not grow.
- The minified release build with IPTV is built in CI (see Validation) but has not
  been installed or smoke-tested on a device.

## Validation

- Night review fixes (phone access, playback, sport matching, recording names), real
  TheSportsDB and live ESPN parser checks, Game Centre formation and cards: CI debug green
  at `c1685393a` (run 38057188837; earlier at `7c7e0e4ae`, run 38055457565); 848 core and
  285 data-layer JVM tests on the host. Minified release (R8) at `c1685393a`: run
  38058607115 (result below once finished). Not device-tested.
- Evening 10 October fixes and the second-capture parser fix: CI debug green at `05cd1484a`
  (run 38047277797); 839 core and 277 data-layer JVM tests on the host. Not device-tested.
- Everything since the 10 October pass (fixes, playback and audio options, menu order,
  external player, source expiry, Recordings/multiview focus, translations): CI debug green
  at `e7e1d17b5` (run 38029912007; compile checks `d78449491` run 38028980919, `422d9d8f0`
  run 38027429837). 834 core and 277 data-layer JVM tests on the host. Not device-tested.
- Fixes from the 10 October pass with translations: CI debug green at `422d9d8f0` (run
  38027429837; code compile check at `769e7bc63`, run 38026419701). 814 core and 276
  data-layer JVM tests on the host. Not device-tested. Build for the next device pass.
- Latest build for the device pass: CI debug green at `01f19bbed` (run 38017089840), same
  app as `3065341` (an app-name clean-up was made and reverted in between). Not device-tested.
- Minified release build (R8) green at `03808ae` (run 38014569747), same code as the
  `3065341` debug build; not installed on a device.
- Remaining sport screens on the new score bands, NHL goalies, race winners and
  translations: CI debug build green at `3065341` (run 38013663627); 788 core and 276
  data-layer JVM tests on the host. Not device-tested. Latest build for the device pass.
- Sport redesign (ledger top panel, team-colour cards on the Sport section, Home rows and
  Games now) and NBL/WNBA/College Football: CI debug build green at `3de7568`
  (run 38012700521); 785 core and 276 data-layer JVM tests on the host. Not device-tested.
- Fixes from the second 9 October device pass, the 10 October ESPN capture checks and
  translations: CI debug build green at `5a64305` (run 38009929728; the run at `16c7406`
  failed on one compile error, fixed in `5a64305`); 774 core and 276 data-layer JVM tests
  on the host. Not device-tested.
- Fixes from the 9 October device pass, widgets, sport follow-ups and translations: CI debug
  build green at `8354d89` (run 37867719176; earlier step `ea3fc4e`, run 37864431259);
  748 core and 276 data-layer JVM tests on the host. Not device-tested.
- Combined ESPN and TheSportsDB sources with translations: CI debug build green at `d3a0288`
  (run 37853846775); 710 core and 271 data-layer JVM tests on the host.
- Parsers checked against real ESPN responses (8 October evening): CI debug build green at
  `374e1f6` (run 37845595141); 697 core and 263 data-layer JVM tests on the host.
- After 8 October: the sports build (shared live service, guide, overlays, Game Centre,
  markers, feeds, multiview data tiles, Sport section, Home rows, pop-ups in Nuvio's player,
  team page, record rules) and the review fixes: 678 core and 263 data-layer JVM tests
  pass on the host (687 core with the later gap fixes); CI debug build green at `6afbea0`
  (run 37791659338, everything incl. translations) and minified release build (R8) green
  at `dd888c0` (run 37795570642); first green with all screens at `9d563f0` (run 37779066258); earlier steps
  green at `f1daa27` (run 37777062519) and `7759873` (run 37774873509). Not device-tested;
  the new sport formats are checked only against synthetic responses built from the
  documented ESPN shapes.
- Latest: debug build green at `ada280b` (run 37767996467) with the fixes from both
  8 October device passes; translations completed in `ecf29d4`. Minified release build
  last green at `ec6f16f` (run 37596443549). Before that: `f29cbe2` (run 37599069380).
- 7 October work after wave 1: 565 core and 233 data-layer JVM tests pass on the host
  harness; device tests compile. CI run 37589504762 at `d0d0dbb` compiled the whole app
  and built the APK with one failing unit test, since fixed (`2bef868`). Not device-tested.
- Wave 1: CI debug build green at `d263b18` (run 37580266979) including translations.

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
  recording and multiview) were done and every finding fixed or answered. Device fixtures and AM9 use are pending. See
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
