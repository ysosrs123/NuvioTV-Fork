# Sports experience — second design pass (8 October 2026)

Design canvas: "Nuvio — Sports experience, second pass"
(https://claude.ai/artifact/NCce5k5kkBJa5aC5MbSiYt, private to the user). First pass:
https://claude.ai/artifact/UY781c11Pg7vBmTZoEtGfW. Nothing here is built yet; the user
chooses what to build.

Mockups use real fixtures, venues, rounds, records and team colours from the 8 October
responses (`IPTV-SPORTS-DATA-FIELDS.md`); live scores, clocks, stats, plays, individual-sport
player names, F1 order and channel names are illustrative. Team marks are monograms.

## Boards

| # | Board | Idea | Data |
|---|---|---|---|
| 1 | Sport section | Hero for the focused game with a match timeline (goals, cards, half-time) and team stats; "Also live"; a "Tonight and tomorrow morning" time ruler with a lane per sport, a now line, Record/Remind per event, events with no channel shown dashed | Scoreboard (have); stats and events need the summary |
| 2 | Fixture cards | One card shape with a sport-specific middle: football and NFL (records), tennis (sets, seed, server, "no point score"), golf (top 5, followed player), F1 weekend (sessions), UFC (main card/prelims split by start time), MLB (bases, outs, count), cricket chase, a spoiler-hidden final, a TheSportsDB-only league (time and final only) | Team sports have; tennis, golf, F1, UFC need new parsers |
| 3 | Guide | "Games now" lane above the rows (score, clock, channel; OK tunes); sport badge in matched cells; live score chip and game progress in the cell; studio shows marked as not a game; close games outlined; "Sport only" filter | Scoreboard and existing matching |
| 4 | Player | Game banner instead of the plain programme banner, "x min behind live"; scoring markers on the catch-up/timeshift bar with Previous/Next score; "Other feeds" panel listing every channel matched to the game (4K, second source as backup, event channels) with optional switch to the backup on a stall | Summary plays; markers exact only where plays carry a wall clock (NHL, MLB; NFL to check), otherwise approximate |
| 5 | Game Centre | Squeeze-back: picture keeps playing scaled down; panel with key moments (each can be watched via catch-up), team stats, "if it ends now" points, line-ups, table, head to head | Summary (`plays`/details, `boxscore`, `rosters`, `standings`, `headToHeadGames`) |
| 6 | Score bug formats | One bug with a sport tail: football (minute, red cards), AFL (goals.behinds), NRL (last try), NFL (possession, down and distance), basketball (scoring run), MLB (bases, outs, count), NHL (power play), cricket (chase), tennis (sets, server), golf (leader + followed player), F1 (session state), UFC (round of rounds) | Each tile says what is in today's scoreboard and what needs more |
| 7 | Overlays | Bug, Glance (appears only on a change, timer bar, OK switches), Cards, Ticker paged by league; skips the game on screen; keeps away from the channel's own bug; alerts held 45 s | Scoreboard |
| 8 | Multiview | Two streams plus a game screen (MLB: bases, count, win probability by at-bat, last plays) and an all-scores screen; data screens use no decoder | Summary (`winprobability`, `plays`) |
| 9 | All scores | By sport and league, live first, close games marked, finished results with recorded games hidden, coming up | Scoreboards; header feed optional |
| 10 | Nuvio Home | "Live sport" row (opens the channel) and "Your teams" row (current or next game per team) above Continue watching | App-wide service (below) |
| 11 | Pop-up in Nuvio's player | Goal pop-up over a film (Watch keeps the film's place, Later, Mute this game), quiet chip version, "While you were watching" when the film ends | App-wide service |
| 12 | Follow a team | Team page (Sydney FC, next game the Sydney Derby on 16 October), record every game rule using the existing padding settings, reminders, alerts per event type, no-spoilers for unwatched recordings; channel matched only once the guide reaches the game | Favourites (have), recording alarms (have) |

## Constraints found while designing

- Picture delay: IPTV runs 20–60 s behind the real game and ESPN can be ahead of it. Alerts
  must be held (default 45 s, adjustable), never fire for the game on screen, and the HUD
  should skip the game the channel already shows.
- Refresh: today every 2 min while live. Pop-ups for followed games need about 30 s for
  those games only. A shared TheSportsDB relay polling every 2 min would lag.
- App-wide features need a small sports service outside Live TV (fixtures are fetched only
  while Live TV is open): followed teams only, live games only, stops when nothing is live.
- Guide days (default 3 ahead) limit channel matching for reminders and recording rules;
  rules must wait for the match and say so if none is found by kick-off.
- Tennis has games per set only (no point score). UFC has no round clock. F1 positions and
  laps are unverified. Golf place and thru are only in the header feed.
- Still unverified live: football situation, running clocks, MLB bases/outs, win
  probability. A capture during live games is needed before building those parts.
- TheSportsDB live scores cover football, basketball, ice hockey and baseball only; its
  other sports are schedule and result only. The relay recommendation stands.

## Recommended order

1. Guide and overlays from data the app already has: sport badges and score chips in
   guide cells, "Games now" lane, score bug/Glance/Cards/Ticker with the hold delay and
   skip-on-screen rule, follow-team alerts inside Live TV, reminders, no-spoilers for
   recordings, the Sport section time ruler. 30 s refresh for followed live games.
2. Summary for the playing game: Game Centre (squeeze-back), scoring markers with
   Previous/Next score, other feeds with backup switching, multiview game and all-scores
   screens. Stream-parse with a size cap; only while open; stop at full time.
3. New sport shapes: tennis, golf, F1 weekend, UFC card, cricket chase, MLB bases/outs,
   NHL power play, AFL goals.behinds; URC rugby; optional header-feed ticker. After a live
   capture confirms the fields.
4. Across Nuvio: app-wide sports service, Home rows, pop-up and chip in Nuvio's player,
   "While you were watching", record-every-game rules and the team page.

Open decisions for the user: which boards to build and in what order; default overlay
(Glance suggested); default alert hold (45 s suggested); whether app-wide alerts may poll
in the background while a film plays; the TheSportsDB relay.
