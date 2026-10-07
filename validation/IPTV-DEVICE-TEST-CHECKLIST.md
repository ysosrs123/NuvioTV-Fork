# Nuvio IPTV — device test checklist (build `f29cbe2`, run 37599069380)

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
2. Download a recording on the phone; "Open in VLC" plays and seeks.

## 7. Sport fixtures
1. Settings → Live TV → Sport → Sports data → ESPN; choose AFL/NRL/EPL.
2. Sport view shows a Fixtures row; selecting a fixture plays a matching channel.
3. TheSportsDB with key 123: same check.

## 8. Experimental local timeshift
1. Settings → Live TV → Local timeshift on, 30 minutes, internal.
2. Full screen on a TS channel: pause 2 minutes, resume, rewind 60 s, Return to live.
3. Watch 30+ minutes; check memory/heat and that nothing stalls.
4. If using USB: remove the drive mid-session — playback should fall back to live.

## 9. General
1. Language: switch the app language once and glance at Live TV screens.
2. Leave Live TV playing for an hour: no freeze, no crash.
