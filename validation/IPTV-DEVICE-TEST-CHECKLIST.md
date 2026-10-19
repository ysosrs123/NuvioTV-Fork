# Nuvio IPTV — device test checklist (build `8724e01f3`, run 39068602774)

For the next build (7x to 7ac, ready to build) start with section 7ac, then 7ab, 7aa, 7z, 7y, 7x, 7w, 7v, 7u, 7t, 7s, 7r, 7q, 7p, 7o, 7n, 7m, 7l, 7k, 7j, 7i, 7h, 7g and 7f (not yet tried on a device). 7e and earlier passed on earlier builds; repeat them only as a regression check. After the 1.1.0-nt.5 merge, also repeat sections 1 and 2.

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

## 7ac. 18 October 23:38 capture: cricket rain delay (build pending)
1. During a rain delay in a live cricket match, the card and hero show "Match delayed by
   rain" and the score bug's third line says the same; once play resumes they show "Live"
   and the run chase again.

## 7ab. 18 October 23:28 capture: penalty shoot-out result (build pending)
1. A finished cup game decided on penalties shows "FT-Pens · <winner> 7–6" on its card,
   score bug and All scores; a game won after extra time still shows "AET".

## 7aa. 18 October 12:33 capture: UFC round clock (build pending)
1. During a UFC bout, the score bug reads "Round 1 of 5 · 3:03" and the time counts down
   with each refresh; in the break between rounds it reads "Round 1 of 5" with no time.
2. Just after a finish, the result line may show the time left ("R2 2:28") for about a
   minute before ESPN corrects it to the time elapsed ("R2 2:32").

## 7z. 18 October 11:27 capture: half-time without a down (build pending)
1. At college football or NFL half-time, the score bug and hero show "Halftime" with no down
   and spot line and no ball marker; in the 3rd quarter the down line and marker return.

## 7y. 18 October 10:28 capture: red zone without a down (build pending)
1. During a college football or NFL timeout or kick-off near a goal line, the score bug
   shows no "Red zone" when there is no down; with a down inside the 20 ("2nd & 5 · SF 8")
   it still shows "Red zone".

## 7x. 18 October 07:10 capture: hockey penalty text (build pending)
1. When an NHL penalty for closing a hand on the puck is the latest play, the Sport card,
   score bug and Game Centre's last plays read "<player> Closing Hand on the Puck", not
   "<player> Minor"; other penalties still read as before ("<player> Tripping against …").

## 7w. 18 October capture: quarter break (build `8724e01f3`)
1. During a college football (or NFL) quarter break, the Sport card, score bug and Game
   Centre show "End of 1st" (not "Q1 · 15:00"); when the next quarter starts they show
   "Q2 · 15:00" and the clock runs.
2. An NHL game just after an intermission still shows "P3 · 20:00" (not "End of 2nd").

## 7v. 17 October captures: TheSportsDB shoot-out and NBA overtime (build `b4af47e5b`)
1. Needs a TheSportsDB key: while a minor-league hockey game from TheSportsDB (QMJHL, AHL,
   ECHL, a European league) is in a shoot-out, its score bug, card and hero show "SO", not
   "PT".
2. A finished NBA overtime game shows "Final/OT" with an OT column in Game Centre and All
   scores; an earlier NFL "Final/OT" game still looks the same.

## 7u. 13–16 October captures: football, baseball, hockey and UFC (build `411ad607f`)
1. Champions League or Premier League live in Game Centre: a substitution reads
   "Substitution, <team>. X replaces Y." within a minute of it happening.
2. A football game with an own goal, before Game Centre is opened: the timeline shows
   "<player> (own goal)" on the side credited with the goal.
3. MLB live: straight after a hit or an out, the score bug count reads 0–0 with the runners
   and outs correct.
4. An MLB game with a run on a wild pitch: Game Centre shows the run once.
5. NHL: after a too-many-men penalty, the power play shows for the other side.
6. A UFC card after the last bout: the card, hero and score bug read "Final", not
   "Scheduled".

## 7t. 21:03 12 October capture: TheSportsDB baseball innings (build `8260658fc`)
1. With a TheSportsDB key and a custom baseball league added (KBO, NPB or CPBL) during a
   game: the Sport card, score bug, hero and multiview tile show "Inning N" (not "IN4"), the
   score updates and no clock is shown. A game with no score yet shows the teams and
   "Inning N".
2. A finished NFL overtime game shows "Final/OT" and an OT column in the hero line score with
   that period's points.

## 7s. Behind-live figure, HLS rejoin and local timeshift pause (build `a7266ce95`)
1. An HLS channel with Safety buffer on: watch 2 minutes at live with Stats open. "Behind
   live" and Return to live do not appear or flicker; Stats shows no "Behind live" row, or
   one that stays under 3 s. (A "Live offset" row in Stats means the stream carries
   programme date-times.)
2. The same channel: pause 30 s and resume. The hint and Return to live show and stay shown
   (no blinking), focus stays on Return to live, and Stats "Behind live" reads about 30 s
   and holds steady. Press Return to live: both go away and stay away.
3. HLS: pause longer than the provider keeps (several minutes) and resume, or pull the
   network briefly: playback returns to live within a second or two without
   "Reconnecting…", and Stats "Reconnects" does not go up.
4. Multiview with an HLS tile: the same long pause rejoins the tile without an error picture.
5. Local timeshift on (TS, full screen): pause for longer than the buffer length. The
   timeline knob and "behind" time stop at the oldest buffered point instead of the pause
   time, and the tag reads "Behind live" (no red dot). On resume, playback starts from the
   oldest point; Return to live goes to live and the tag reads "Live" again.
6. Local timeshift on a channel without catch-up: scrub back past the start of the buffer;
   the target stops at the oldest point.
7. Open question, please note the result: a TS channel with Safety buffer Off and local
   timeshift off. Pause for 5 minutes, resume, and note what Stats "Behind live" shows
   (about 300 s, a small number, or nothing), whether Return to live is offered (with the
   buffer off it is not expected to be), and whether the picture continues from the pause
   point or jumps to live.

## 7r. 11:43 12 October capture: basketball last plays (build `1e539a53a`)
1. A live NBA or NBL game whose last play is a free throw: the Sport hero sentence starts
   with a capital ("Misses free throw 2 of 2."), not lower case.
2. The multiview game tile at an NBA tip-off: the last plays show "Jump Ball", not "vs.".
3. Team plays without a player (e.g. "Delay of game violation", "Shot clock turnover") start
   with a capital in the multiview tile's last plays.

## 7q. 11:26 12 October capture: baseball count and last plays (build `1e539a53a`)
1. During a live MLB game, the Sport hero shows outs and the count (e.g. "2 outs · 3–2")
   with no "Pitch N : Ball N." text below; that line shows the last at-bat result, the
   at-bat starting, or nothing.
2. In Game Centre or the multiview tile last plays for a live MLB game, a balk (or another
   runner play) appears once.
3. Multiview with a live MLB game tile: the count, outs and bases match the Sport hero
   (they no longer lag a pitch behind).

## 7p. 9:40 12 October capture: NHL empty net (build `98d520804`)
1. A live NHL game where the trailing team pulls its goalie late in the 3rd: the Sport hero
   goalie line and the Game Centre summary "Goalies" show only the other team's goalie while
   the net is empty, and both again once the goalie returns (within about 30 s). The Stats
   tab lists both throughout.
2. The same game at the end of a period, and an NHL game with both goalies in: both
   goalies are shown.
3. While the net is empty, the hero goalie line, the Game Centre summary (under Goalies) and
   the multiview game tile read e.g. "SEA empty net"; it can lag by up to about 30 s, and it
   goes away when the goalie returns. With scores hidden it does not show.

## 7o. 8:01 12 October capture: NFL half-time and overtime finals (build `2d693cada`)
1. NFL at half-time: the score bug reads Halftime with no timeout marks; they come back in
   the 3rd quarter.
2. With TheSportsDB as the source, an NFL or hockey game that ended in overtime or a
   shoot-out shows as finished with "Final/OT" (or "Final/SO").

## 7n. Merge of 1.1.0-nt.5, second-pass fixes and the 6:42 12 October capture (build `91e1e7f1b`)
1. After installing: the sidebar shows Live TV next to Home and, if turned on, Calendar;
   Live TV opens and Back behaves as in section 1. Home shows Coming up first, then the
   Live TV rows.
2. Nuvio's player (a movie or an IPTV recording): the Speed button is there; a recording
   still has no watch party button.
3. Start a recording: the notification shows the app's notification icon.
4. Settings → Appearance → app icon: choose one of the new icons; the launcher still names
   the prototype "Nuvio IPTV Prototype".
5. TheSportsDB as the NFL source: a game in overtime past three and a half hours still shows
   live with "OT · m:ss".
6. ESPN NFL: straight after a touchdown, the score bug does not show "Red zone" while the
   conversion is pending; inside the 20 the down line is accented.
7. All scores: on the day strip press Left, then Down straight away: the day does not change
   and focus stays on the list. Future days show only the kick-off time on each line; in
   German or Russian, counts such as "4 Ergebnisse" are not cut off.
8. Sport page on a quiet day: Up from a card to "All scores", then Down lands on the cards.
9. League picker: untick a league added from TheSportsDB, then "Add a league" and Back to the
   picker: its tile is still there, unticked. Leave the picker with it unticked: it is gone.
10. Headings in Game Centre, All scores, game cards, Home widgets and the Live TV rail are in
    sentence case, not spaced capitals.
11. Multiview: hide the score on one picture, close and reopen multiview: still hidden until
    you leave Live TV. Change "Channel and programme", export the setup backup and import
    it on another box (or after clearing data): the choice comes back.
12. 1080p box, a game with a short Sport hero: the two teams' facts sit side by side and
    "Last met" still shows.
13. Recordings: press OK twice quickly on a recording: it opens once. Press Back while
    "Preparing the recording…" shows on a NAS recording, then open it again: it opens
    normally (no stuck connection).
14. Delete a recording while it is still recording (and, with a WebDAV share, while it
    uploads): no leftover files on the box or the share.
15. Guide catch-up: OK on a programme right at the edge of the archive does not stop the
    channel that is playing.
16. A golf tournament on day 3 or 4: still on the Sport page and in All scores as live. The
    morning after it finishes it shows under "Earlier today", with a catch-up link where
    your provider has catch-up.
17. A TheSportsDB hockey or football game shown live hours after it should have ended now
    shows the final score.
18. A TheSportsDB league you added that is now built in (e.g. Top 14), with "Record every
    game" on for a team: the rule is still on, the team page shows its games, and the next
    game is recorded.
19. Follow the New York Jets only: Giants games are not marked as followed or recorded.
20. All scores: past days show no count until opened, then "Loading results…", then the
    results.
21. Open a tennis or golf game in Game Centre and bring the controls up several times:
    nothing goes wrong.
22. NFL game in overtime: the score bug shows two timeout marks per team; college overtime
    shows one.
23. Record an NFL game that goes to overtime: the recording carries on until the game ends;
    with the app open it stops your "keep going" time after the final. The Record every
    game panel reads "…, and on while a game runs over".

## 7m. Live NFL and NASCAR from the 12 October capture (build `85fe99dce`)
1. Live NFL game, score over the video: three small bars beside each team logo show the
   timeouts left; the side with the ball has the dot.
2. When a team is inside the opponent's 20, the score bug's note turns to the accent colour
   (or reads "Red zone") and the Sport hero adds "Red zone" after the down and distance.
3. Sport hero during a drive: after the last play, a line such as "KC drive: 8 plays, 57
   yards, 4:12."; it disappears once the drive ends.
4. Game Centre, a multiview game tile and the full-screen game info for a live NFL game: a
   small dot after the name of the team with the ball.
5. Last plays (NFL and college football) start with the play itself, without "(Shotgun)" or
   "No Huddle-Shotgun".
6. With TheSportsDB as the source for an NFL game: the card shows the quarter and clock,
   e.g. "Q4 · 3:08".
7. TheSportsDB hockey (e.g. DEL): a game decided by a shoot-out shows as finished; a score
   stuck on the third period long after the game should have ended is not shown as live.
8. Formula 1 or NASCAR after the race: the score over the video shows the podium, not the
   circuit; during a NASCAR race it shows the lap and running order.
9. Tennis, golf, F1, NASCAR and UFC games open in Game Centre, multiview and full screen
   without errors (no game summary is requested for these).

## 7l. Fourth and fifth 11 October captures: results, awards, live tennis and F1 (build `d4fc94d24`)
1. A finished UFC card: the card shows the main event result, e.g. "Allen won ·
   Submission · Round 5, 2:46" (decisions and draws show without round and time).
2. A finished cricket match: the card shows the result, e.g. "Australia won by 112 runs".
3. Game Centre after a finished game: the result line and awards where the source has
   them (player of the match, winning and losing pitcher, save, winning goalie, first star).
4. NHL game decided in a shootout: the line score labels the column "SO"; a game with more
   than one overtime shows 2OT, 3OT.
5. Rugby: a half-time score of 0–0 that contradicts the tries listed is not shown.
6. Lower-league hockey games (e.g. AHL, USHL via TheSportsDB) that ended are not shown as
   live hours later.
7. Live tennis: sets and games update and the server is marked; no point score (the source
   does not send it).
8. Live Formula 1 race: the Sport hero shows "Race · Lap N" with the top three; the card
   shows "Lap N"; the score over the video shows the running order, not the next session.
9. Football at half-time: the score over the video shows "HT", not "45+1'".
10. TheSportsDB games in overtime read "OT" with the minute; games without a status stop
    showing as live once they should have finished.

## 7k. Sport page, multiview and schedule look (build `428ec7c99`; try on the 4K TV and a 1080p one if you can)
Sport page:
1. On a quiet day the row fills with "Earlier today" results, tomorrow's games and "Your
   teams next", each with a small label; no single card left on its own; the D-pad moves
   through them without getting stuck on the labels.
2. No text ends in "…": hero broadcaster line (e.g. "USA Net +1"), the "+5 feeds" button,
   "Watch on …", card footers ("in 7 h 26 min").
3. Pre-game hero (football, NRL, rugby): stay on a game for a second; each team shows five
   form marks (latest on the right), table position and points, and a "Last met" line
   with date and score. Compare one team with the league table.
4. Game cards all the same height; live dot and clock, kick-off time or FT always top
   right; scores aligned on the right; about 4 cards across; crests sharp on the 4K TV.
5. The hero's top line shows both teams' colours, visible with Pure black on and off.
Multiview:
6. A picture showing a live game has a slim strip: crests, short names, score (trailing
   team dimmed), clock or kick-off. With hidden scores the score is a grey bar.
7. Tile menu → Hide score on this picture / Show score on this picture works per picture.
8. Tile menu → Channel and programme: Always / Selected picture only / Off. Channel name and
   programme with a progress line along the bottom of every picture, non-sport too; on a
   game both share one strip.
9. Strips readable in 2×2 and in the small pictures of other layouts; long names cut
   cleanly.
All scores:
10. Day strip above the list (Yesterday, Today, days ahead) with counts ("3 live",
    "6 games", "4 results", "No games"); Today selected when it opens.
11. Pick a past day: "Loading results…", then the results; hidden scores stay hidden (Hold
    OK reveals); going back to that day is instant.
12. The Live, My teams and sport filters work on other days.
League picker:
13. Live TV settings → Sport → Leagues opens a full screen: sports down the left with
    chosen/total counts; leagues as tiles with badge, name, source and a tick.
14. OK toggles a league; Left from the first column goes to the sport list; Back saves and
    returns to the Leagues row; games of a newly picked league appear on the Sport page.
15. "Add a league" still opens the TheSportsDB search; the "not linked to any league" note
    is shown.

## 7j. Second and third 11 October sport captures (build `7741e4029`)
1. Live baseball (Game Centre and multiview score tile): count, outs and bases show during
   an at-bat; between innings ("End"/"Mid") the score shows no "0 outs · 0–0" and the
   multiview tile hides the empty bases; last plays list at-bat results, not every pitch;
   pitcher and batter are named.
2. NHL: after offsetting penalties (same number of minors each side) no power play shows;
   with an extra minor on one side, the other side shows the power play.
3. College football at midfield reads "4th & 10 at 50".
4. TV channel lists (TheSportsDB key on): worldwide services such as NBA League Pass appear
   after your country's channels.
5. New competitions (e.g. J1 League, Brasileirão, Copa Libertadores, Copa del Rey, League
   One): turn one or two on; their games appear (ids checked against both providers'
   lists in the capture).
6. Baseball: after a hit or walk the count shows 0–0 and the runner is not shown as the
   batter.
7. Basketball: at the end of a quarter the score shows "End of 3rd" (not "Q3 · 0.0").
8. UFC: before a bout starts the score and the Sport hero show "Walkouts" or "Pre-fight",
   not "Round 1".
9. College football last plays: conversions read e.g. "Extra Point Good (… KICK)";
   touchdowns read as a short line.

## 7i. Recording playback and guide catch-up (build `4d0c61b39`)
Recordings (use last night's NAS recording and a new one):
1. Open last night's recording: "Preparing the recording…" shows briefly (note how long),
   then the timebar shows the real length (about 3 h, not 24 h).
2. Long seeks (e.g. jump to 1:30:00 and back to 0:10:00): quick, and the picture matches
   the time shown. On the old recording a seek may be a few seconds out; note how far.
3. Watch a few minutes, back out, open it again: it resumes there. The recording's options
   show "Resume from …" and "Play from start"; the row shows a progress line.
4. Watch to near the end (within a minute, or 1/50 of the length for long recordings: about
   3½ minutes for 3 h), back out: next time it starts from the beginning.
5. Make a new short recording (to the NAS and to device storage) with a network drop if
   you can: length correct, seeks exact, no "Preparing" step (a recording cut short by the
   app closing may show "Preparing" once).
6. No watch party button when playing a recording; IPTV movies and series still have it.
7. NAS asleep when opening a recording: note whether the first seconds stall or retry.
Guide catch-up (on a channel with the history mark):
8. Left from the current programme walks back through earlier programmes; the time bar
   scrolls back; earlier rows fill in as you go (note any blank flash or delay).
9. Past programmes on catch-up channels look normal with a small history mark; OK plays
   from the start. Past programmes on other channels are dimmed (is the dimming right?).
10. Rewind / Fast-forward jump 3 hours; the time bar shows the day when not today.
11. Back returns to now; the next Back opens the menu. Left at the earliest reachable
    programme opens the menu, also on a channel with only now/next listings and after a
    failed earlier-guide load (Left again retries).
12. A programme older than the provider's archive: OK says "No catch-up for this
    programme" and does not stop what is playing.
13. With a corner picture playing, Rewind/Fast-forward still work in the guide.

## 7h. 11 October sport capture, more leagues and full-share recordings (build `f79646c53`)
1. Live TV settings → Sport → Leagues: the list is grouped by sport, each league showing its
   sport and source underneath; scrolling the longer list is smooth.
2. Turn on one or two of the new leagues (e.g. Ligue 1, MLS, Six Nations): their games
   appear on the Sport page. Rugby: check Premiership Rugby, Top 14 and Champions Cup show
   games (their ESPN ids are not confirmed by a capture).
3. If you had added a league through TheSportsDB search that is now built in (e.g. Top 14),
   it shows once, still selected, and your favourite teams for it are kept.
4. Guide linking: programmes for games in the new leagues show the score/Watch on link; a
   programme that only mentions another competition in its description is not linked.
5. College football live (Saturday US time): last play without a "(08:05)" prefix; down and
   distance shown; the timeline is not squashed.
6. NHL live: a penalty shows a power play for the other team straight away.
7. Live rugby: the clock is not stuck on 1'; half scores do not show "0 0".
8. Football Game Centre: substitutions read "X replaces Y".
9. Recording to a network share that is full (or nearly full): the recording row shows
   "Share full – upload paused until there's space" in red; after freeing space (enough for
   the next 256 MB part plus 100 MB; for a WebDAV share without partial upload, the whole
   recording plus 100 MB) it clears within about a minute while recording, or for a finished
   recording at the next retry (up to 15 minutes) or when you open Recordings.
10. Keep the share full: the recording stops once about 2 GB is waiting on the box, marked
    "The network share is full"; it uploads after space is freed. Also with a WebDAV share
    that has no partial upload.
11. Box storage low while recording to a share: the reason says the TV box's storage.

## 7g. Fixes from the night review of 10 October (build `c1685393a`)
1. Phone access: right after starting the app, a paired phone cannot open a PIN-protected
   profile's settings or recordings; the phone change prompt never shows over the profile
   screen or profile management.
2. Live TV settings → Playback and audio: with tunnelling on, check the log for
   `live tunnel engaged=true/false`; with `false`, volume boost and surround lift still work.
3. Tunnelling on: play a channel, open Live TV settings, wait 30 s, come back. The picture
   returns; if not, it re-tunes itself within about 10 s.
4. Nuvio passthrough to a receiver: the first channel after a cold start already uses
   passthrough (no stereo start), and tuning has no extra pause.
5. Multiview with HEVC channels: the number of pictures allowed matches the box (no extra
   tile that then stutters).
6. Sport → Watch on: a channel whose guide lists both teams comes before a preferred
   channel matched only by league or broadcaster.
7. Sport channel rules: channels picked for "Always in Sport only" always appear on Sport
   only, including after a playlist refresh; category picks work whatever the case.
8. Sport only on a large guide: games later today (up to 24 hours ahead) are listed; up to
   200 channels; Sport opens faster than before.
9. Stalker portal with an expiry date: the date shown matches the portal's own.
10. Recording to a network share or the Movies folder twice with the same programme and
    start minute (or with an existing file of that name): the new file is saved as
    " (2)"; the existing file is untouched. Delete a recording while it is still uploading:
    only its own file goes.
11. Game Centre line-up: booked and sent-off marks show in your language.
12. Sport page: Down from the last card row reaches the Sport only toggle; Up from the
    toggle goes back to the cards; nothing gets stuck.

## 7f. Fixes from the evening 10 October pass (build `05cd1484a`)
1. Live TV settings: toggle Pure black and the colour theme; focus stays on the toggle.
2. Main guide: no Sport only / All channels switch; all channels shown. Sport page: Down
   from the cards reaches the switch, Up returns to the last card.
3. From the guide (on a programme) open Recordings, Settings, Sources, phone setup and
   Movies/Series, then Back or Left: focus returns to the same menu item or the same
   programme and scroll position. Same after closing channel/category options and search.
4. While a channel plays (corner or full screen), open Live TV settings / Recordings /
   Sources / phone setup: sound continues; on return the picture is back without re-tuning.
   Playing a recording stops the channel.
5. Multiview with four pictures: the focused one keeps its size with a thin border.

## 7e. Playback options, menu order, external player and source expiry (build `e7e1d17b5`)
1. Live TV settings → Playback and audio: frame rate / resolution matching "Same as Nuvio"
   shows Nuvio's value; set Live TV only values and check the TV switches on a 50 fps channel.
2. Passthrough with your receiver: "Same as Nuvio" passes AC-3/E-AC-3 (receiver shows Dolby);
   "Off" sends PCM. Multiview and the corner picture always decode in the app.
3. Tunnelled playback on (needs "4K and HDR in the corner picture" on and Automatic decoder):
   full screen 4K/HDR smoothness; zapping; back to the guide; add to multiview (reopens).
4. Prefer surround audio (5.1) on: a channel with stereo + 5.1 starts on 5.1. Prefer audio
   language works; a track picked by hand still wins.
5. Audio decoder: Prefer app decoder plays everything (Dolby decoded in the app, no
   passthrough); back to Automatic.
6. Live TV menu: hold OK on an item → Move in menu / Hide from menu; Search, Settings,
   categories and Sources cannot be hidden; hidden items listed dimmed; Reset menu.
7. Channel hold OK → Open in external player: plays in your external player with headers;
   Live TV stops; message when no player is installed.
8. Sources: Xtream sources show "Expires dd-MMM-yy · N connections" or "No expiry"; a source
   within 7 days shows the warning there and under the Live TV menu title.

9. Recordings: Back and Left return to Live TV with the menu open on Recordings (from
   Home or Live TV settings: back to where you came from).
10. Multiview add/replace list: Left/Right between categories and channels never lands on
    the pictures behind it.

## 7d. Fixes from the 10 October pass (build `422d9d8f0`)
1. Stream info widget in the short stacked size: two labelled columns (video, audio,
   bitrate, buffer); other sizes use their space.
2. Guide info panel: long description fills down to the hint line; long titles wrap to two
   lines in slightly smaller type.
3. Live TV menu → Settings opens Live TV settings inside Live TV (no jump to Nuvio's main
   Settings); Back returns to the menu on Settings. Main Settings has no Live TV category.
4. Phone access: setup screen → "Keep phone access on" → pair the phone once; close the
   setup screen; the phone still reaches setup and recordings (Nuvio open or in the
   background). A change from the phone asks for OK on the TV wherever you are. Paired
   phones list: Remove works; "Forget this device" on the phone works.
5. Multiview: add a third and fourth 1080p channel: the others step down or it plays; if
   refused, the message says what to do. Keep the `multiview device` / `multiview budget=`
   log lines if it refuses.
6. Left from the guide many times (also quickly after scrolling): focus always lands in the
   menu; Sport panels never keep focus behind the open menu.
7. Live TV menu: Sport next to Movies and Series.
8. Sport page guide: scroll across the next 24 hours; Sport only lists every channel with a
   game in that time.
9. Sport settings → Sport channels: Always / Never in Sport only; Preferred / Never offer for
   games (check "Watch on" picks a preferred channel).
10. Record something: file name like "Channel - Title - 10-Oct-26 1355.ts" (no code at the
    end); phone download names use the same date.

## 7c. Fixes from the second 9 October pass, Sport redesign and new leagues (build `01f19bbed`)
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
