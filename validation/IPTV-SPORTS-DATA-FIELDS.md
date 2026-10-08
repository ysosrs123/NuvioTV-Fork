# Sports data fields — ESPN and TheSportsDB

Reference for what the two fixture services return and what Live TV uses. Checked against
real responses saved on 8 October 2026 (ESPN scoreboard and summary for NFL, AFL, NRL
`rugby-league/3`, Premier League `soccer/eng.1`, A-League Men `soccer/aus.1`, NBA and one
cricket league; ESPN NFL teams, news and standings and Premier League standings;
TheSportsDB v1 with the free key `123`). None of the samples had a game in progress, so
the live fields (situation, last play, live clock) are from ESPN's known shapes and the
existing synthetic tests, not from these files. Trimmed copies of the scoreboard and
TheSportsDB samples are JVM test fixtures (`app/src/test/resources/sports/`,
`SportsFixturesRealDataTest`).

Parsers: `core/iptv/SportsFixturesParsers.kt` (`EspnScoreboard`, `SportsDbEvents`).
"Used" means the app reads it today. Both services are unofficial or rate-limited; the
app fetches one scoreboard per league and service day (3-day window), refreshes every
30 min, every 2 min while a game is on or about to start, backs off after failures, and
runs at most two requests at a time.

## Endpoints

| Endpoint | Returns | Used |
|---|---|---|
| ESPN `site.api.espn.com/apis/site/v2/sports/{sport}/{league}/scoreboard?dates=YYYYMMDD` | Events for one US-Eastern day with teams, status, scores, broadcasts | Yes |
| ESPN `.../{sport}/{league}/summary?event={id}` | One event in depth: box score, leaders, plays, win probability, odds, rosters, standings, news | No |
| ESPN `.../{sport}/{league}/teams`, `.../news`, standings | Team list with logos and colours; articles; tables | No |
| TheSportsDB `eventsday.php?d=YYYY-MM-DD&l=League_Name` | Events for one UTC day | Yes |
| TheSportsDB `lookupevent.php?id=`, `lookuptimeline.php?id=`, `lookupeventstats.php?id=`, `lookuplineup.php?id=`, `lookuptv.php?id=` | One event; goals/cards; team stats; line-ups; TV channels | No |
| TheSportsDB `eventsnextleague.php`, `eventspastleague.php`, `lookuptable.php`, `lookupteam.php` | Next/past events, table, team details | No |
| TheSportsDB v2 livescore | Live scores and progress | No — needs a paid key |

Free key `123`, observed: `eventsday` for soccer returned 3 events (USL League One), for
AFL `{"events":null}`; `eventsnextleague`/`eventspastleague` returned 1 event each;
`lookuptable` returned 5 rows of the previous season (2025-2026); timeline, stats and
line-up returned 5 rows each. Expect a paid key to return full lists.

## ESPN scoreboard (per event; `c` = `events[].competitions[0]`)

### Event and schedule

| Field | Example | Sports | Used |
|---|---|---|---|
| `events[].id` | `401872980` | all | Yes (fixture id) |
| `c.date` / `events[].date` | `2026-10-09T00:15Z` | all | Yes (start) |
| `events[].name`, `shortName` | `Tampa Bay Buccaneers at Dallas Cowboys`, `TB @ DAL` | all | Fallback title only |
| `events[].week.number` / root `week.number` | `5` | NFL, AFL, NRL (missing for soccer, NBA) | Yes (Week / Round) |
| `c.status.type.state` | `pre` / `in` / `post` | all | Yes (status) |
| `c.status.type.name` | `STATUS_SCHEDULED`, `STATUS_FINAL`, `STATUS_FULL_TIME` | all | Yes (skips postponed, cancelled, delayed) |
| `c.status.type.completed` | `true` | all | Yes |
| `c.status.type.shortDetail`, `detail` | `10/8 - 8:15 PM EDT`, `Final` | all | Yes (live detail fallback) |
| `c.format.regulation.periods` | `4` (NFL, NBA), `2` (NRL) | most | No |
| `c.neutralSite`, `c.attendance`, `c.notes[]`, soccer `c.altGameNote` | | all | No |
| `events[].season.year`, `slug` | `2026`, `regular-season` | all | No |

### Teams

| Field | Example | Sports | Used |
|---|---|---|---|
| `c.competitors[].homeAway` | `home` | all | Yes |
| `team.displayName`, `shortDisplayName` | `Dallas Cowboys`, `Cowboys` | all | Yes (names, matching) |
| `team.abbreviation` | `DAL`, `SYD` | all | Yes (logo fallback, win bar) |
| `team.location`, `team.name` | `Dallas`, `Cowboys` | all | Yes (matching alternatives) |
| `team.color`, `alternateColor` | `002a5c` (cricket: `#f3f702`) | all but NRL | Yes (`color` only) |
| `team.logo`, `logoDark` | `https://a.espncdn.com/i/teamlogos/nfl/500/scoreboard/dal.png` | all | Yes (`logo`) |
| `records[]` (`type: total`) `.summary` | `2-2` (NFL), `4-0-1` (soccer W-D-L), `19-4` (AFL) | all | Yes, numbers only |
| NRL `records[].summary`, soccer/NRL `form` | `WWWLL` | NRL, soccer | No (form, not a record) |
| `curatedRank.current` | `99` | NFL | No |

### Scores and periods

| Field | Example | Sports | Used |
|---|---|---|---|
| `competitors[].score` | `"19"`; cricket `"166 (39.1/50 ov, target 279)"` | all | Yes (up to 24 characters) |
| `linescores[].displayValue`, `value`, `period` | NBA `32,29,38,24`; AFL `22,21,29,17` | NBA, NFL, AFL, NHL, MLB | Yes (period scores) |
| NRL `linescores` | `6, 19` plus placeholders for period 20 and 60 | NRL | Yes — cumulative half-time/full-time, converted to per-half |
| Cricket `linescores` | `{period, runs, wickets, overs, isBatting}` per innings | cricket | No (innings, not periods) |
| Summary `header.competitions[0].competitors[].linescores` (AFL adds `goals`, `behinds`) | `22` (3.4) | AFL | No |

### Live situation

| Field | Example | Sports | Used |
|---|---|---|---|
| `c.status.period` | `2` | all | Yes (Q2, 2H, P3) |
| `c.status.displayClock` | `8:42`, soccer `67'`, NRL `80'` | all | Yes while live |
| `c.situation.downDistanceText`, `shortDownDistanceText`, `possessionText` | `3rd & 7 at DAL 35` | NFL | Yes |
| `c.situation.possession` | team id | NFL | Yes (possession dot) |
| `c.situation.lastPlay.text` | | NFL, NBA, MLB | Yes |
| `c.situation.lastPlay.probability.homeWinPercentage` | `0.49` (fraction) | NFL, NBA | Yes (win bar) |
| MLB `situation.balls/strikes/outs/onFirst…` | | MLB | No |

### Statistics and leaders

| Field | Example | Sports | Used |
|---|---|---|---|
| `competitors[].statistics[]` `{name, abbreviation, displayValue}` | NBA `rebounds 43`; NRL zone stats | NBA, NRL, soccer | No |
| `competitors[].leaders[]`, `c.leaders[]` | `Passing Leader: Dak Prescott 1065 YDS` | NFL, NBA, AFL, soccer | No |

### Odds

| Field | Example | Sports | Used |
|---|---|---|---|
| `c.odds[].details`, `overUnder`, `spread`, `moneyline`, `pointSpread`, `total` | `DAL -8.5`, `47.5` | NFL, soccer (not AFL/NRL/NBA in these samples) | No (not planned) |

### Venue, weather, broadcasts

| Field | Example | Sports | Used |
|---|---|---|---|
| `c.venue.fullName`, `address.city`, `indoor` | `AT&T Stadium`, `Arlington` | all | Yes (`fullName`) |
| `events[].weather.displayValue`, `temperature` (°F) | `Mostly sunny`, `85` | NFL | No |
| `c.broadcasts[].names[]` | `Prime Video`, `USA Net`, `ESPN+` | all (empty for AFL, NRL here) | Yes (channel matching) |
| `c.geoBroadcasts[].media.shortName`, `type.shortName`, `region` | `Peacock`, `Streaming`, `us` | all | Yes (`shortName`) |
| `c.broadcast` (string) | `Prime Video` | all | No (same as above) |
| `c.tickets[]` | | NFL, soccer | No |

Broadcasters are US networks even for the Premier League and A-League; for Australian
leagues the lists were empty, so channel links there come from guide titles.

### Logos and images

| Field | Example | Used |
|---|---|---|
| `leagues[0].logos[0].href` | `https://a.espncdn.com/i/teamlogos/leagues/500/nrl.png` | Yes |
| `team.logo` | see Teams | Yes |
| Leaders/roster `athlete.headshot` | player photo | No |

## ESPN summary (`/summary?event=`), not used

| Section | Content | Sports in samples |
|---|---|---|
| `header` | Same event header as the scoreboard, linescores (AFL goals/behinds) | all |
| `boxscore.teams[].statistics[]` | Team stats (`FG 37-85`, AFL `Kicks 203`); before kick-off NFL/soccer give season averages | all |
| `boxscore.players[]` | Player stat tables (`names`, `labels`, `athletes[].stats[]`) | NBA, AFL, NRL |
| `leaders[]` | Top players per category with `mainStat`, `summary` | NBA, NFL, AFL, soccer |
| `plays[]` | Play-by-play: `text`, `period.number`, `clock.displayValue`, `homeScore`, `awayScore`, `scoringPlay`, `type.text` (NBA 469, AFL 55 entries) | NBA, AFL (NFL uses `drives` when live) |
| `winprobability[]` | `homeWinPercentage` (0–1), `tiePercentage`, `playId`, one per play | NBA (NFL empty before kick-off) |
| `predictor` | `homeTeam.gameProjection` `86.1` | NFL |
| `pickcenter[]`, `odds[]`, `againstTheSpread[]` | Odds | NFL, NBA, soccer |
| `rosters[]` | Line-ups: `jersey`, `starter`, `position`, `captain`, `subbedIn` | soccer, NRL |
| `gameInfo` | `venue`, `attendance`, `officials[]`, NFL `weather` | all |
| `injuries[]` | Status, type, return date | NFL, NBA |
| `lastFiveGames[]`, `headToHeadGames[]`, `seasonseries[]` | Form and head-to-head | most |
| `lastTen` | Last 10 scores, inside 50s, frees | AFL |
| `standings.groups[].standings.entries[]` | Short table | most |
| `news.articles[]`, `article`, `videos[]` | Headlines and media | most |
| cricket `matchcards[]`, `notes[]` | Batting/bowling cards, series notes | cricket |
| `meta.syncUrl`, `wallclockAvailable` | Live sync hints | NBA |

## TheSportsDB v1 `eventsday` / `lookupevent` (per event)

| Field | Example | Used |
|---|---|---|
| `idEvent`, `idLeague`, `strLeague` | `2494042`, `4328`, `English Premier League` | Yes (league check) |
| `strTimestamp` | `2026-09-20T15:30:00` (UTC, no offset) | Yes (start) |
| `dateEvent`, `strTime` | `2026-09-20`, `15:30:00` | Yes (fallback) |
| `dateEventLocal`, `strTimeLocal` | `16:30:00` | No |
| `strStatus` | `NS`, `FT` | Yes |
| `strPostponed` | `no` | Yes |
| `strHomeTeam`, `strAwayTeam`, `strEvent` | `Fulham`, `Manchester United` | Yes |
| `intHomeScore`, `intAwayScore` | `"1"`, `null` before kick-off | Yes |
| `intRound` | `5` | Yes |
| `strVenue`, `strCity`, `strCountry` | `Craven Cottage` | Yes (`strVenue`) |
| `strHomeTeamBadge`, `strAwayTeamBadge`, `strLeagueBadge` | `https://r2.thesportsdb.com/images/media/...` | Yes |
| `strPoster`, `strSquare`, `strThumb`, `strBanner`, `strVideo` | event art, highlight video | No |
| `strProgress` | minute while live (livescore only) | Yes if present |
| `strTVStation` | — absent from every 2026 sample | Read, but always empty now |
| `strHomeTeamShort`, `strAwayTeamShort` | — absent | Read, always empty |
| `strOfficial`, `strWeather`, `intSpectators`, `strResult` | mostly empty | No |

Other endpoints (not used):

| Endpoint | Fields |
|---|---|
| `lookuptimeline` | `strTimeline` (`Card`, `Goal`, `subst`), `strTimelineDetail`, `intTime`, `strPlayer`, `strAssist`, `strHome`, `strTeam` |
| `lookupeventstats` | `strStat` (`Passes accurate`), `intHome`, `intAway` |
| `lookuplineup` | `strPlayer`, `strPosition`, `intSquadNumber`, `strSubstitute`, `strHome`, `strCutout` |
| `lookuptv` | `strChannel` (`Stöð 2 Sport IS`), `strCountry`, `strLogo`, `strTimeStamp` — the source of broadcasters now |
| `lookuptable` | `intRank`, `strTeam`, `strBadge`, `intPlayed`, `intWin`, `intDraw`, `intLoss`, `intGoalDifference`, `intPoints`, `strForm`, `strDescription` |
| `lookupteam` | `strTeamShort`, `strTeamAlternate`, `strColour1-3`, `strBadge`, `strLogo`, `strFanart1-4`, `strBanner`, `strStadium`, `idESPN` |

## Mismatches found against the real responses

Fixed (`SportsFixturesParsers.kt`, `SportsFixturesRealDataTest`):
- NRL `linescores` are cumulative (`6`, `19` for a 19-point total) and include
  placeholder periods 20 and 60; the hero showed `6 19 0 0`. Periods above 12 are now
  dropped and cumulative lines (rising, last equals the total, sum does not) are turned
  into per-period scores (`6`, `13`).
- NRL `records[].summary` is recent form (`WWWLL`) and was shown as a win-loss record.
  Only `n-n`, `n-n-n` style records are shown now.
- Cricket `linescores` describe innings (`displayValue` is the innings number) and were
  shown as periods; lines with `runs`/`wickets` are ignored. (Cricket has no ESPN league
  in the app yet.)

Known, not fixed:
- TheSportsDB no longer sends `strTVStation` in `eventsday`; broadcasters need one
  `lookuptv` request per event, so fixtures from TheSportsDB link to channels only
  through guide titles.
- TheSportsDB has no short team names (`strHomeTeamShort` absent); full names are shown.
- ESPN broadcasters are US-centric; the A-League and Premier League lists name US
  networks, AFL and NRL lists were empty.
- Cricket scores longer than 24 characters are dropped.
- Live fields (situation, clock while live, probability) could not be checked: no game
  was in progress in the samples.

## Ideas for a playback stats panel

All from the ESPN summary endpoint for the playing fixture (one request returns
everything below; 100–550 KB in the samples, so parse in a stream or with a size cap).

- Box score: per-period line and total (already in the scoreboard).
- Team stats: possession, shots, kicks, disposals, rebounds, yards (`boxscore.teams`).
- Leaders: top 1–2 players per category with headshot (`leaders`).
- Scoring plays: last 5 from `plays[]` where `scoringPlay` is true (NRL/soccer:
  `c.details[]` tries and goals with clock and player, already in the scoreboard).
- Play-by-play: last 3 `plays[].text` with clock.
- Win probability: latest `winprobability[]` value and a small line chart (NBA, NFL).
- AFL momentum: `lastTen` scores and inside 50s.

Cost: one summary request per refresh for the playing fixture only. At one request every
30 s while the panel is open that is 2 requests a minute (120 an hour); every 60 s is
1 a minute. Combined with the scoreboard refresh (one request per league and day every
2 min while live) a 3-hour game with the panel open costs about 360 summary requests at
30 s. Fetch only while the panel is visible, stop when the game is final, and keep the
same back-off rules. TheSportsDB equivalent (timeline + stats + line-up) is three
requests per refresh and returns only 5 rows each with the free key.

## Coverage comparison (from responses downloaded 8 October 2026)

TheSportsDB (premium key, v2):
- `all/sports`: 37 sports; `all/leagues`: 1,547 leagues (soccer 692, fighting 140,
  basketball 123, ice hockey 60, rugby 57, motorsport 52, skiing 41, cricket 34, golf 31,
  volleyball 30, ... netball 11, darts 3, snooker 3, Gaelic 22, esports 23, Australian
  football 4). v1 `all_leagues.php` returns the same 1,547.
- `livescore/all`: 85 games, only ice hockey (43), basketball (35), soccer (6) and baseball
  (1) at the time; every other sport answers `{"Message":"No data found"}`. The pricing
  page lists livescores for soccer, NFL, NBA, MLB and NHL only. Fields: teams and ids,
  badges, scores, status, progress, league, timestamp — no clock detail, stats or plays.
- `list/teams/{league}` and `search/team/{name}` work; `schedule/next/league/4456` (AFL)
  returned no data (off-season).

ESPN (core API `sports` and `{sport}/leagues`):
- 17 sports, ~350 leagues: soccer 219, MMA 48, rugby union 25, basketball 15, baseball 12,
  golf 9, ice hockey 6, American football 5, racing 5 (F1, IndyCar, three NASCAR series),
  lacrosse 4, tennis 2 (ATP, WTA), volleyball 2, water polo 2, field hockey 1, Australian
  football 1 (AFL), rugby league 1 (NRL, id 3). Cricket returns no leagues from this list
  (the app's cricket league id comes from the site API).
- Live detail (clock, situation, plays, box score, win probability) per game via `summary`.

Only in TheSportsDB: netball, darts, snooker, cycling, handball, esports, Gaelic games,
skiing and other winter sports, athletics and smaller codes — mostly schedules, results,
teams and artwork, not live scores.

Suggested split: ESPN for live detail in the leagues it covers; TheSportsDB for breadth
(schedules, results, teams, artwork across 37 sports) and for live scores in smaller
soccer, basketball and ice hockey leagues ESPN lacks.
