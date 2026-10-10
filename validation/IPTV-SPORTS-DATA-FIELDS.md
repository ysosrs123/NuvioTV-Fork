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

## ESPN second pass (8 October 2026)

Second set of real responses: scoreboards for EPL, UCL, A-League, NFL, NCAAF, NBA, NBL,
NHL, MLB, AFL, NRL, United Rugby Championship (`rugby/270557`), ATP, WTA, PGA, F1,
NASCAR, UFC and cricket; summaries for all team leagues except F1/NASCAR/UFC/golf/tennis;
plus the multi-sport header feed. No team game was in progress. Live data was seen only
in golf (event state `in` between rounds) and tennis (3 matches `in` in each tour file).

### Multi-sport header feed

`site.web.api.espn.com/apis/v2/scoreboard/header?region=au` (418 KB). Shape:
`sports[]` → `leagues[]` (`slug`, `abbreviation`, `isTournament`, `smartdates[]`, 3 dates)
→ `events[]`. One request returned 8 sports, 11 leagues, 24 events:

| Sport | League (`slug`) | Events |
|---|---|---|
| Baseball | `mlb` | 4 |
| Basketball | `wnba`, `nba` | 2, 5 |
| Ice hockey | `nhl` | 3 |
| Football | `college-football` | 2 |
| Tennis | `wta`, `atp` | 2, 2 |
| Golf | `pga` | 1 (25 players) |
| Volleyball | `womens-college-volleyball` | 1 |
| Soccer | `usa.ncaa.w.1`, `usa.ncaa.m.1` | 1, 1 |

All 24 events were `post` (dated 7 October 20:00Z to 8 October 05:45Z). Absent despite
`region=au`: AFL, NRL, NBL, A-League, EPL, UCL, rugby union, cricket, F1, NASCAR, UFC,
and also NFL (15 games scheduled that night). Broadcasts are US networks; links point to
`espn.com.au`.

Per-event fields (flat, not under `competitions`):

| Field | Example | Notes |
|---|---|---|
| `status`, `summary` | `post`, `Final`, `Final/OT`, `FT`, `Round 1 - Play Complete` | ready-made ticker text |
| `fullStatus.type.{name,state,detail,shortDetail,altDetail}`, `fullStatus.displayPeriod`, `periodPrefix` | `STATUS_FINAL`, `OT`, `9th`, `End` | |
| `clock`, `fullStatus.displayClock`, `fullStatus.clock` (s), soccer `addedClock` | `5:00`, `90'` | missing for MLB, tennis, golf |
| `period` | `9`, `5` (OT) | |
| `competitors[]` `{homeAway, winner, displayName, abbreviation, color, alternateColor, score, logo, logoDark, record}` | `NY` `98`, `26-18` | no `linescores` for team sports |
| NHL `competitors[].goalieSummary[].displayValue` | `5 GA, 22 SV, .815 SV%` | |
| MLB `onFirst`, `onSecond`, `onThird` (0/1), `outsText`, `baseRunnersText`, `competitors[].summaryAthletes[]` (`STARTING_PITCHER`) | `0 Outs`, `Bases empty` | 4 MLB events; values seen only after the game |
| `seriesSummary`, `note`, `competitionType.text` | `CHW lead series 2-1`, `ALDS - Game 3` | playoffs |
| `odds` | `provider.name` `TAB Betting`, `homeTeamOdds.favorite` | 4 events (MLB, NCAAF); no spread or total in any |
| `broadcasts[]` `{type, name, shortName, isNational, region}` | `TBS`, `ESPN Radio` | 15 events |
| Tennis `competitors[].linescores[]` `{value, setScore, winner}`, `score`, `notes[0].text` | `6-3 7-5`, `... bt ... 6-3 7-5` | |
| Golf `competitors[]` `{place, movement, amateur, score, status.{teeTime, hole, thru, state, todayDetail}}`, event `displayPurse`, `defendingChampion` | `1`, `-8`, `-8(F)`, `$8,000,000` | top 25 of 72 |

Could it replace per-league scoreboard polling for the HUD? Only partly. It is one
request for many leagues and carries ticker-ready text, but: (1) the league list is
curated by ESPN and US-centred, with none of the Australian leagues or the EPL/UCL;
(2) it lagged or filtered — tennis showed 2 finished matches per tour while the tour
scoreboards had 3 live matches each, and golf showed `post` while the golf scoreboard
event was `in`; (3) no period lines for team sports and no situation fields were seen.
Use it for a cross-sport ticker or "elsewhere" row; keep per-league scoreboards for
fixtures, period scores and Australian leagues. Live fields (clock while running,
football situation, MLB base runners during play) remain unverified here.

### Individual sports not handled yet

The app's parser assumes `events[].competitions[0]` with two teams. These do not fit:

| Sport | Shape (`e` = `events[]`) | Seen | Card / HUD could show |
|---|---|---|---|
| Golf (PGA) | One `e`; `e.status.type.state` `in` while `e.competitions[0].status` was `STATUS_PLAY_COMPLETE` (`Round 1 - Play Complete`, period 1). `competitors[]` = 72 players: `order` (1–72, ties not marked), `score` (to par, `-8` … `+10`), `athlete.displayName`, `shortName`, `flag.href`, `statistics` (empty for all 72), `linescores[]` per round (`value` 63 strokes, `displayValue` `-8`, `period` 1, nested 18 holes with `value`, `period` = hole, `scoreType.displayValue` `E`/`-1`/`+1`; round 2 only `{period: 2}`). Round `statistics.categories[0].stats` are 7 unlabelled values. No place, thru or tee time on the scoreboard (the header has them). | 164 KB; Baycurrent Classic, 8–11 Oct; `Golf Chnl`; `leagues[0].calendar` | Event, round status, top 5 with flag and to-par, viewer's player highlighted; HUD: leader and score |
| Tennis (ATP, WTA) | `e` = tournament (`name`, `venue.displayName`, `major`, `previousWinners[]`); `e.groupings[]` (`grouping.slug` `mens-singles`, `womens-doubles`, …) → `competitions[]` = matches with `round.displayName`, `venue.court`, `status` (`detail` `3rd Set`), `notes[0].text` (`... leads ... 1-6 6-3 5-4`). `competitors[]` `{order, homeAway, winner, curatedRank.current (seed), athlete.displayName, flag}`; doubles `type: team`, `roster.displayName` `F. Reynolds / J. Watt` style. `linescores[]` per set `{value, winner, tiebreak}`; current set has no `winner`. Live WTA matches had `possession` (server) true/false; the 2 live Shanghai ATP matches had none. No point score within a game. `format.regulation.periods` was 5 for every match in the ATP file and 3 in the WTA file, so it is not reliable. | ATP 836 KB: 2 tournaments, 385 matches (275 post, 3 in, 107 pre). WTA 804 KB: 3 tournaments, 343 matches (304 post, 3 in, 36 pre). One China Open match is in both files. No match had broadcasts. | Live matches first: names, seeds, set games with tiebreak, serve dot, round, court; filter by `state` and stream-parse (size) |
| F1 | One `e` (Singapore Grand Prix, `circuit.fullName` `Marina Bay Street Circuit`, `circuit.address`); `competitions[]` = 5 sessions, `type.abbreviation` `FP1`, `SS`, `SR`, `Qual`, `Race`, each with own `date`, `status`, `broadcasts` (`Apple TV`). `competitors` empty before each session. | 11 KB; all `pre`; `calendar` 25 | Weekend card with the session list and local times; next session as the fixture for guide matching. Results/driver fields unverified. |
| NASCAR | League `nascar-premier`; one `e` (`NASCAR Cup Series at Charlotte`, 11 Oct 19:00Z), one competition, 0 competitors, `broadcasts` `USA Net`, `HBO Max`; no venue or circuit field. | 12 KB; `calendar` 40 | Race name, start time, channel. Running order unverified. |
| UFC | One `e` (`UFC Fight Night: Allen vs. Duncan`, `venues[0]` Meta APEX, Las Vegas); `competitions[]` = 12 bouts: `type.abbreviation` weight class (`Middleweight`, `W Flyweight`), `format.regulation.periods` 3 (main event 5), 2 `competitors` (`order`, `winner`, `athlete.displayName`, `athlete.flag`, `records[0].summary` `27-7-0`), `status.displayClock` `-`, `broadcasts` `Paramount+`. Main event is the last bout. No card-segment field; 7 bouts at 21:00Z and 5 at 00:00Z mark prelims and main card. | 34 KB; all `pre` | Card headline from the last bout, main card and prelims split by start time, records, weight class |

### Summaries compared with the first pass

| League | State | New or different |
|---|---|---|
| MLB (CLE 9 at CHW 3, ALDS) | post, 1,085 KB | `plays[]` 645 (`type.text` `Ball`, `Play Result`, …; `alternativeType` `Double`/`2B`; `period.type` `Top`/`Bottom`; `outs`; `pitchCount`/`resultCount` balls-strikes; `onFirst`/`onSecond`/`onThird` `{athlete.id}`; `pitchType`, `pitchVelocity`; `wallclock`); `atBats` 84 and `playsMap` 645 (`$ref` into `plays`); `winprobability[]` 84, one per at-bat (`homeWinPercentage` 0.739 first, 0.0 last). Scoreboard `situation` was null after the game. |
| NBL (Cairns 97–91 Brisbane) | post, 242 KB | `plays[]` 409 with `clock`, `period`, `text`, `shortDescription`, `scoringPlay`, `shootingPlay`, `pointsAttempted`, `coordinate` (placeholder values for non-shots); no `wallclock`. `winprobability` present but empty; `standings.groups` empty; `leaders` and `boxscore.players` for both teams. |
| NHL (PIT at WSH) | post, 469 KB | `plays[]` 307 with `strength`, `shotInfo` (8), `wallclock`; `onIce[]`; no `winprobability`. |
| UCL | pre, 199 KB | `standings.groups[0]` 36 entries (league phase), stats `GP W D L GD P`. |
| A-League Men | pre, 106 KB | standings 12 entries (same stats); `odds`, `pickcenter`, `rosters` present. EPL: 20 entries. |
| URC (Glasgow Warriors v Connacht) | pre, 114 KB | `headToHeadGames[]` 1 entry (`team` + 5 `events` with `score`, `homeTeamScore`, `awayTeamScore`, `gameResult`, `opponent`, `gameDate`); NRL has the same shape (5 events). Standings use `standings.children[].standings.entries` (16 entries, 28 stats incl. `BP`, `TBP`, `LBP`), not `groups`. `rosters` empty, `odds`/`pickcenter` empty lists, team box score stats empty before kick-off. Scoreboard `records[0].summary` is form (`TWLWW`), already rejected by the record filter. No broadcasts. |
| NCAAF | pre, 112 KB | `predictor`; `winprobability` empty; standings 18 (Big Ten, stats `overall`, `CONF`). |
| NFL, AFL, NRL, NBA, EPL, cricket | as first pass | AFL `plays` 55, NBA `plays`/`winprobability` 469 (same game as before). |

Still unverified (no team game in progress in either pass): scoreboard `situation`
(0 of all team competitions had one), `lastPlay` and its probability, running
`displayClock`, NFL `drives`, MLB balls/strikes/outs and base runners while live, NBL
live win probability, and live header fields.

### What the app could add next

1. Tennis: parse `groupings` → matches; show live matches with set games, tiebreaks,
   seed and serve dot. Filter to `state` `in`/`pre` while parsing (800 KB responses).
2. Golf: leaderboard card (top 5 by `order`, to-par, flag, round status) from the
   scoreboard; take place and thru from the header when present.
3. Event-with-sessions sports: F1 weekend (5 sessions) and UFC card (12 bouts) as one
   fixture with a session/bout list; NASCAR as a single race fixture.
4. Rugby union (URC `270557`): fits the existing team parser; add the league and read
   standings from `children` as well as `groups`.
5. MLB HUD line from the header (`outsText`, `baseRunnersText`, starting pitchers) and
   summary `winprobability` per at-bat for the win bar.
6. Optional cross-sport ticker from the header feed, alongside (not instead of)
   per-league scoreboards.
7. Capture again during live NFL, AFL, NRL, EPL, NBA and MLB games to verify the
   situation, clock and probability fields.

## Third pass (8 October evening)

Real responses captured about 07:55 Sydney time on 9 October (20:55Z on 8 October) for
the same leagues, checked against the parsers that had only been built from synthetic
JSON. No team game was in progress; golf was `in` between rounds; tennis had only `post`
and `pre` matches. Trimmed copies are in `app/src/test/resources/sports/*-real-20261008.json`.

| Shape | What the real response showed | Parser change |
|---|---|---|
| Tennis | Tour files mix draws: the ATP file carries the China Open women's singles and doubles, the WTA file its men's draws (`grouping.slug`, `competitions[].type.slug`). 80 ATP and 24 WTA matches had `timeValid: false`, date `04:00Z` and detail `M/d - 'TBD'`; 50 had `TBD` players (negative ids). Short names follow the player's own surname order (`Zheng Qinwen` → `Q. Zheng`; `A. Lazaro Garcia`); doubles have `roster.shortDisplayName`. `STATUS_RETIRED`, `STATUS_WALKOVER` (no `linescores`) and `STATUS_CANCELED` occur. `curatedRank.current` matched the seed in all 871 notes checked; `tiebreak` is per side (`6-7 (4-7)` → 6/tb 4, 7/tb 7). | Keep only the tour's own draws; drop untimed scheduled matches and `TBD` players; surname from the short name; no `0–0` score for walkovers; scheduled detail from `shortDetail` |
| Golf | 72 players; no `displayPurse`, venue, place or `status.thru` on the scoreboard. Each player's round card is `linescores[]` (`period` = round, `displayValue` = round to-par, nested `linescores[]` = holes played, hole number in `period`, back-nine starters begin at 10). | `thru` (`F` at 18) and today's score from the current round's hole count |
| Cricket | Every innings is listed for both teams; the fielding side gets `runs` 0, `wickets` 0, `isBatting: false` and the batting side's `overs`. `isBatting` marks the side that batted that innings, `isCurrent` the current innings. Score text `166 (39.1/50 ov, target 279)`. | Skip the fielding copies; batting = `isBatting` and current; score before ` (` |
| AFL summary | 55 `plays`, all goals/behinds/rushed, none with `scoringPlay`. Ladder entries: `team` is the abbreviation string, stats `points` (TP), `form`, `percentage`. Team stat is `totalClearances`. | Goal/behind/rushed plays become moments; `points` shown as `PTS` with `PER` |
| NRL / URC summary | Header `linescores` are cumulative with two trailing `0` entries and no `period`; URC before kick-off has four `0` entries. Every roster entry has `starter: false`; starters are the players whose `position.name` is not `replacement`/`reserve` (an NRL hooker wore 19). Details types are lower case (`try`, `conversion`, `penalty goal`, `drop goal`, `substitute on`); no `scoringPlay`. Team stats are union-style names, many 0 for league. | Trailing zeros trimmed before the cumulative check, no periods without a score; position-based starters sorted by jersey; kicks as points; league/union stat lists |
| Standings | Points are `name: points` with abbreviation `P` (soccer, URC), `TP` (AFL), `PTS` (NHL) or `matchPoints` `PT` (cricket). | Shown as `PTS` everywhere so the "ends now" points work |
| Pre-game summaries | `boxscore.teams` hold season values (NFL `Points Per Game`, MLB season `hits` 1282, NBA `streak` `-`, `avgPointsAgainst` 0.0). | Rows that are `-` or 0 for both teams are left out (unless preferred) |
| F1, NASCAR, UFC | Matched the synthetic shapes: F1 `FP1`/`SS`/`SR`/`Qual`/`Race`, NASCAR one competition with no venue, UFC bouts sorted by `order`, 7 prelims at 21:00Z and 5 main-card bouts at 00:00Z. | none |

Still unverified (needs a capture during play): tennis `possession` and in-play set
scores, golf `status.thru`/`todayDetail` and a part-played round card, F1 session results
and live session state, UFC live round and results, cricket live `isBatting`/`isCurrent`
and overs while batting, MLB situation and count, scoreboard `situation`/`lastPlay`,
running clocks, soccer `keyEvents` and live rosters (`subbedIn`/`subbedOut`), NRL/URC
details `period` on the scoreboard (absent after the game), live team stats for rugby
union, and whether the NHL/MLB pre-game season stats should be shown under team stats.

## Combined sources (9 October 2026)

The app no longer has a source choice. Per league: ESPN path → ESPN; otherwise TheSportsDB
when the user's own key is saved (never the free key `123`, which TheSportsDB's terms do
not allow in published apps). ESPN failures (half or more of a league's days failed twice)
switch that league to TheSportsDB until ESPN answers again. Extras with a key: v2
`livescore/{sport}` (`X-API-KEY` header) merged by event id into TheSportsDB fixtures, at
most 3 requests per refresh and no more often than every 2 min; v1 `lookuptv.php` for
followed teams' games (ESPN games matched to TheSportsDB events by teams and start within
6 h), cached 24 h per event; league list (`all_leagues.php` / v2 `all/leagues`) cached
7 days for "Add a league". Response shapes for livescore, lookuptv and the league list
are from documentation and the 8 October coverage files; confirm with a real capture.

## Fourth pass (10 October morning)

Real responses captured about 10:50 Sydney time on 10 October (23:50Z on 9 October):
scoreboards for all leagues above plus NBL and college football, summaries for the team
leagues, the multi-sport header, and a summary of a live NHL game. In play: three NHL
games (all at the end of the 1st period) and PGA round 3 (6 of 72 players had started).
Everything else was `pre` or `post`; no tennis match was live. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261010.json` (`SportsRealDataMorningTest`); the
synthetic NHL summary was replaced by the real one.

| Shape | What the real response showed | Parser change |
|---|---|---|
| NHL scoreboard (live) | `status.type.name` `STATUS_END_PERIOD`, `state` `in`, `period` 1, `displayClock` `0:00` (counts down), `shortDetail` `End of 1st`. `situation` holds only `lastPlay` (`End of 1st Period`, no probability). Header `linescores` per period without a `period` field. `competitors[].statistics` are goalie figures (`saves`, `savePct`) plus skater totals, not shots. | none |
| NHL summary (live) | `plays[].clock` counts **up** within the period (`9:19`, period end `20:00`), unlike the scoreboard clock. Every play has `wallclock`. `strength` (`Even Strength`, `Power Play`, `Shorthanded`) is from the acting team's point of view and is on almost every play, including the period-end play (no team). `boxscore.teams` shots `shotsTotal`, `powerPlayGoals`/`powerPlayOpportunities`; goalies in `boxscore.players[].statistics[name=goalies]` with `keys` (`saves`, `shotsAgainst`, `savePct`). No `winprobability`, no `situation`. | Moment markers treat hockey play clocks as elapsed; even strength gives no strength line; `Shorthanded` becomes a power play for the other side; goalies parsed (`SummaryGoalie`) |
| Header (NHL) | `goalieSummary[].displayValue` `1 GA, 17 SV, .944 SV%` per side while live; `fullStatus.clock` 0.0. | none (header not used) |
| Golf (mid-round) | Scoreboard still has no `status`, place or `thru`. A started player's current-round line has nested hole lines (first hole `period` 10 for back-nine starters) and `displayValue` = today. A player yet to start has the round line with no holes; the last value of its unlabelled `statistics.categories[0].stats` is the tee time as text, e.g. `Fri Oct 09 21:36:00 PDT 2026`, which is US Eastern wall-clock time despite the label (the header gave `teeTime` `2026-10-10T01:36Z` for the same 25 players). Header `place` ties match ties computed from to-par. | Tee time (`teeMillis`) for players yet to start; country as the three-letter flag code (`USA`, `JPN`) for a fixed-width column |
| Tennis | Doubles match tiebreaks are a third "set" of `value` 1–0 with the points in `tiebreak` (`1-0 (10-7)`); a retirement during one is `0-0 (4-7)`. Still no live match, so `possession` and in-play sets remain unseen. | Match tiebreak shown as its points (`7`–`10`) instead of `0(7)`–`1` |
| F1 | Completed sessions (FP1, sprint qualifying) list all 22 drivers with `order` = classification; no times or gaps (`statistics` empty). The next session lists drivers without `order`. Event-level `status` was `post` (period 28) while the weekend was still running; the parser ignores it and follows the sessions. | Top three per finished session (`RaceSession.top`) |
| NFL summary (final) | No root `plays`; play-by-play is in `drives.previous[].plays` (no `team` on the play), scoring summary in `scoringPlays[]` (`team`, `text`, `homeScore`, `awayScore`, `clock`, no `wallclock`; ids match drive plays). `winprobability` 184 entries. | Moments from `scoringPlays` (wallclock taken from the matching drive play); last plays from drives |
| URC scoreboard | Same cumulative `linescores` with scores at 20' and 60' in periods 20 and 60. One final (Glasgow 36–19) had 0 at half-time and 0 at 20'/60' although tries were scored before the break. | none — shown as given (`0 36`); the half-time line is missing at source |
| NBL, college football | Both parse cleanly with the team parser (NBL quarters 10 min; NBL plays have no `wallclock`). Neither is in the app's league list. | none |
| Cricket, AFL, NRL, A-League, EPL, UCL, MLB, NBA, UFC, NASCAR | Same shapes as the third pass; MLB and NHL pre-game summaries again carry season totals as team stats. | none |

Confirmed from real data on 10 October: NHL live status/period/clock/detail and score
lines, NHL `lastPlay` on the scoreboard, NHL plays with strength, goals and wallclock,
NHL team stats and goalie lines, golf mid-round thru/today, tee times, tied positions,
doubles match tiebreaks, F1 practice and sprint qualifying classification order, NFL
scoring plays, drives and full-game win probability.

Still unconfirmed (need a capture during play): NHL running clock within a period and a
power play in progress at capture time; NFL `situation` (down and distance, possession,
`lastPlay` probability) and `drives.current`; MLB balls/strikes/outs and base runners;
NBA/NBL running clock and live win probability; tennis `possession` (serve) and in-play
set scores; F1 race classification, live session state and gaps; NASCAR running order;
UFC live round, result method and winner; cricket live `isBatting`/`isCurrent` and
overs; soccer `keyEvents`, live clock and `subbedIn`/`subbedOut`; AFL/NRL/URC live
clock and details `period`; golf leaderboard during a round with most of the field on
the course (only 6 had started) and after a cut (this event has none).

## Fifth pass (10 October evening)

Real responses captured about 19:07 Brisbane time on 10 October (09:07Z). In play: one NBL
game (Phoenix v United, 2nd quarter), six ATP matches in Shanghai and three WTA qualifying
matches in Wuhan. PGA was between rounds (event `in`, round `STATUS_PLAY_COMPLETE`), not
mid-round. `nhl-summary-LIVE.json` and `nbl-summary.json` in this capture are byte-for-byte
the morning files, so NHL adds nothing new. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261010b.json` (`SportsRealDataLiveTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NBL scoreboard (live) | `STATUS_IN_PROGRESS`, `period` 2, `displayClock` `5:56` with `clock` 356.0 (counts down), `shortDetail` `5:56 - 2nd`. `linescores` carry `period`. `situation` holds only `lastPlay` (`text`, `scoreValue`, `team`, `athletesInvolved`), no probability, possession or fouls. No venue or broadcasts. | none |
| NBL summary (live) | `format.regulation` `periods` 4, `clock` 600 (overtime 300). Header `linescores` without `period`; competitors also carry `timeoutsUsed`/`timeoutsRemaining`, `fouls.bonusState` and `possession` (false for both). 138 `plays`, clock counting **down** (`9:42` … `6:03`), `period.number`, `homeScore`/`awayScore`, `scoringPlay`, `team.id` only; no `wallclock` (`wallclockAvailable` false). `winprobability` empty, no `situation`. Team box score and `leaders` (points, assists, rebounds) filled in. | none — 10-minute shape puts 2nd 5:56 at 40 min after the start; the capture was 37 min after the scheduled tip-off |
| Tennis (live) | In-play set has no `winner`; an unfinished 6–6 set can carry `tiebreak` points (`8`–`6`). `possession` (server) appears only on matches that also have a `situation` object (baseball-style `onFirst`… all false); 5 of 9 live matches had neither. `status.period` = current set, `detail` `3rd Set`. | none |
| Golf (between rounds) | Every player has the finished round (18 holes, `F`) and an empty next-round line `{"period": 4}` with no tee time; the header has round-4 `teeTime` and `todayDetail` `-6(F)`. | none (event stays live with the round status as detail) |
| URC summary | Header lines `0, 36, 0, 0` (half-time missing at source, two trailing zeros). | Rugby summary lines drop trailing zero entries after the second half (`0 36`, as on the scoreboard) |
| Others | AFL, NRL, A-League, EPL, UCL, NFL, college football, MLB, NBA, NHL, F1, NASCAR, UFC and cricket all `pre`/`post` with the shapes above; NHL shootout final has a fifth line (`Final/SO`). | none |

Confirmed now: NBL/basketball live status, running clock (counting down) on scoreboard and
plays, live per-quarter lines, `lastPlay`, live box score and leaders, 10-minute quarter
markers; tennis live sets, current set, serve and in-set tiebreak points; golf between rounds.

Still unconfirmed: NFL/college `situation` (down and distance, possession, probability) and
`drives.current`; MLB balls/strikes/outs and runners; NBA live win probability (NBL has
none); NHL running clock and a power play in progress; tennis match tiebreak in play; F1
live session state, classification and gaps; NASCAR running order; UFC live round, method
and winner; cricket live `isBatting`/`isCurrent` and overs; soccer `keyEvents`, live clock
and substitutions; AFL/NRL/URC live clock and details `period`; golf with the field on the
course and after a cut.
