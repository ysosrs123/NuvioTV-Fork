# Nuvio IPTV — device test checklist (build `3065341`, run 38013663627)

For this build start with section 7c (fixes from the second 9 October pass), then 7b, 7 (Sport) and the rest.

Mark each item pass / fail / not tested. For failures, note what happened and keep a
logcat (`adb logcat -s NuvioIptv:V *:E`). Do not share logs that contain addresses.

## 1. Setup and navigation
1. Add sources again (the reinstall cleared them): Xtream, then M3U if you use one.
2. Sidebar shows Live TV; opening it shows the full-width guide.
3. Left from the guide opens the Live TV menu; Left again opens Nuvio's sidebar; Right returns.
4. Back: closes full screen / menu first, then opens the sidebar, then the exit prompt.
5. Live TV menu → Settings opens main Settings at the Live TV category with focus inside it.
6. No sidebar or top bar over full screen or multiview.

## 2. Live playback and stability
1. Zap through 10 channels: start time, picture, sound.
2. Stats overlay: buffer now/target, protocol, HTTP/3 advertised, reconnects.
3. Settings → Live TV → Streaming: try Start Fast / Normal / Safe; Safety buffer 20 s.
   Watch 10 minutes: the buffer should grow towards 20 s; listen for any pitch or lip-sync issue.
4. With a receiver/soundbar on passthrough (AC-3), check whether the buffer still grows.
5. Pull the network cable or turn Wi-Fi off for 3 seconds: playback should continue or recover without a buffering wheel.
6. "Return to live" button appears when behind live and returns to live; fast-forward key does the same.
7. Per-source user agent (Sources → source menu → User agent): change it and confirm channels still play.

## 3. Movies and series
1. Live TV menu shows Movies and Series (Xtream source).
2. Browse grid: categories, Recently added, search, paging speed with a large catalogue.
3. Settings → Live TV → Movies and series → artwork Nuvio: posters change to TMDB art.
4. A matched movie opens Nuvio's detail page with an "IPTV · source" stream; play it.
5. An unmatched title opens the Live TV title page; play, stop, Resume.
6. Settings → Playback → Stream selection → IPTV movies and series on: a movie page found through
   Nuvio search shows the IPTV stream.
7. One-connection account: play a movie, then open Live TV and tune a channel — expect the
   "connections in use" message; stop the movie and the channel should play.
8. Continue Watching and Trakt (if used) show a matched movie after watching part of it.
9. The player has no "open in external player" for IPTV movies.

## 4. Home rows
1. Home shows Live TV favourites (mark a few favourites first), Sport on now, Recordings.
2. A channel card opens Live TV full screen on that channel.
3. Settings → Live TV → Home screen switches hide/show rows.
4. Check Classic, Grid and Modern home layouts if you use more than one.

## 5. Recording
1. Record a programme to internal storage; play it back.
2. USB drive (FAT32 and exFAT/NTFS if available): record over 30 minutes; play back.
3. Network location: SMB, WebDAV or FTP — Test, Save, record 10 minutes, play back from the share.
   For a NAS with its own certificate, "Trust this certificate" should appear.
4. Movies folder (Android 10+): record, then check the file appears in another app's Movies folder.
5. Box asleep / TV off during a scheduled recording: recording still completes.

## 6. Phone setup page
1. Open phone setup, pair, open the Recordings card: list shows recordings.
2. Download a recording on the phone; it plays in a video player app.

## 7. Sport fixtures
1. Settings → Live TV → Sport: Sport fixtures on; choose AFL/NRL/EPL in Leagues; check the
   credit line "Sports data from ESPN and TheSportsDB" and Team logos.
2. Sport view shows the fixture cards; selecting a fixture plays a matching channel.
3. Optional, with your own TheSportsDB key: add it, then "Add a league" and pick a league
   only TheSportsDB has (e.g. netball); its fixtures appear (time, channel, final only).
4. Sport settings: follow two teams (hold OK on a fixture); Show scores on; While watching
   → Glance; Across Nuvio → Pop-up.
5. Sport view: cards per sport (tennis, golf, F1, UFC if chosen), hero Follow / Remind me,
   "Tonight" timeline (Left/Right through events, OK opens channels), All scores pill;
   recorded unwatched games show masked scores, hold OK reveals.
6. Guide: league badges, live score chips and progress, "Games now" lane above the rows
   (Up from the first row; OK tunes), Sport only filter.
7. Full screen on a live game: game banner; Info opens Game Centre (picture shrinks,
   tabs, Watch on a key moment, Back closes); score markers on the catch-up/timeshift bar
   with Previous/Next score; Feeds lists other channels and the same channel on other
   sources; another followed game scoring shows a Glance card about 45 s later (OK
   switches, Back dismisses); nothing pops up for the game on screen; the channel list
   (Left) and number entry are not interrupted by a card.
8. Multiview: add a Game screen and an All scores tile; they need no decoder slot.
9. Nuvio Home: Live sport and Your teams rows; team page (Follow, Remind me, Record every
   game). Play a film: a followed team's score shows a pop-up (Watch the game resumes the
   film from Continue watching later); none in the last 10 minutes; Show scores off hides
   the score in the pop-up.
10. Reminder: set one 6 minutes ahead; it appears 5 minutes before kick-off in Live TV, in
   Nuvio's player and on Home.
11. Press Home on the remote with Game Centre open: logcat should show sports requests
   stop (`sports fetch` lines cease).
12. Useful logs: `sports fetch service=`, `sports links`, `sports fixtures`.

## 7c. Fixes from the second 9 October pass, Sport redesign and new leagues (build `3065341`)
1. Guide top area: no "Live TV · profile", update text or clock; a small spinner beside the
   guide heading during a guide update; the grid keeps programmes during the update.
2. Widgets: hold OK → Layout: try all eight layouts (tall, square, wide, two stacked, two
   tall, tall + two short, large + two small squares, four squares); text fits small tiles.
   Stream info shows badges, video/audio details, buffer and dropped frames.
3. Hold Up from low in the guide: rows scroll to the top first, then a fresh Up goes to the
   widgets. Left/Back opens the menu on Search channels. "Leave Live TV?": Leave to Home first.
4. 4K/HDR channel in the corner picture: picture shows (check smoothness, whether the TV
   switches to HDR, square corners); Settings → Live TV → Streaming → "4K and HDR in the
   corner picture" off brings back the logo.
5. Full screen → Back to the guide: no stutter. Full screen Left/Right never change channel
   (Right opens the controls).
6. Quiet 4K HLG AAC channel: louder? Stats overlay audio row (codec, channels, gain) and
   "Start time" row — please note the audio row if still quiet.
7. Multiview: from full screen hold OK → Add to multiview: no restart. Left in the multiview
   channel list opens categories (also from Sport). Sound follows focus in every pane. Add a
   third stream from the second source: it plays or a message says why.
8. Live TV Sources: Refresh all sources / Refresh all guides with progress; new guide import
   speed; the guide stays usable during an import.
9. Phone setup: add a source → the page stays open with "Add a guide for this source"; add a
   guide assigned to that source.
10. Recordings: play one (local and network share if you use one): Nuvio's normal player with
    seek bar and tracks; Back returns to Recordings.
11. IPTV movie and series (signed out and signed in): Nuvio's detail page; Play starts the
    IPTV stream; series seasons and episodes; resume.
12. Sport: no clipped headings or card borders; timeline lanes whole; pre-match hero with big
    logos, countdown and "4W 0D 1L"; golf columns aligned; Premier League logo visible;
    Sport on by default on a fresh install.
13. Settings → Live TV → Sport → TheSportsDB key: Show button aligned; dialog opaque. Other
    Live TV settings dialogs opaque.
14. QR sign-in now present (CI builds include the keys from your secret).
15. Sport new look: top panel ledger (live, pre-match, final; golf, tennis, racing,
    cricket); score cards with team-colour bands and logos (Sport lanes, Home rows, Games now
    strip, Game Centre, team page, All scores, multiview score tiles, widgets, the pop-up in
    Nuvio's player). Check text fits, no clipping, spoiler-free hides scores.
16. NHL game: goalie saves in Game Centre and the top panel. Racing card: session winners.
17. Sport settings → leagues: NBL, WNBA, College Football available (off by default); NBL
    timeline uses 10-minute quarters.

## 7b. Fixes from the 9 October pass (build after `8354d89`)
1. Back: full screen → guide → Live TV menu → "Leave Live TV?" (Stay / Leave to Home).
2. Full screen: Left = channel list, Left again = categories (choose one), Left/Back closes.
3. Left on Live TV Sources returns to the guide; Left on Movies/Series returns to Live TV
   with the menu open.
4. Guide: Compact by default (fresh install or never changed), slightly larger titles,
   bigger corner picture, at least six rows.
5. Widgets between the programme details and the corner picture: Up from the guide (past
   the Sport only toggle and Games now lane), hold OK to choose; try World clocks (add and
   remove cities), Up next, Recordings, Stream info, Sport strip; Layout 1/2/3.
6. No see-through dialogs or panels anywhere in Live TV or its settings.
7. Scroll the guide and press OK on many channels quickly: the "previous player has not
   confirmed it stopped" message should not appear; if it does, keep the logcat lines
   `live close started`, `live close ms=`, `live close unconfirmed reason=`.
8. Record a channel whose name has decorative letters: the recording name is plain text.
9. Open an IPTV movie and a series (signed out of Nuvio): the IPTV details page opens
   with the provider's plot and artwork; playback and resume work.
10. Phone setup: Live TV menu → "Set up from your phone or computer".
11. Sport: a match tomorrow shows a channel when your guide lists it; options offer Record
    or "Record when a channel is found"; Sport settings → "Find channels using".

## 8. Experimental local timeshift
1. Settings → Live TV → Local timeshift on, 30 minutes, internal.
2. Full screen on a TS channel: pause 2 minutes, resume, rewind 60 s, Return to live.
3. Watch 30+ minutes; check memory/heat and that nothing stalls.
4. If using USB: remove the drive mid-session — playback should fall back to live.

## 9. General
1. Language: switch the app language once and glance at Live TV screens.
2. Leave Live TV playing for an hour: no freeze, no crash.
