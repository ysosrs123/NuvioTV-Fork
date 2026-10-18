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

## Sixth pass (10 October night)

Real responses captured about 23:12 Sydney time on 10 October (12:12Z). In play: the first
live football match seen (Arsenal v Leeds United, 41', 0–0, two Leeds yellow cards), a
second NBL game (Perth v New Zealand, 2:51 left in the 4th) and five ATP and two WTA
matches. PGA was still between rounds (byte-for-byte the fifth-pass file); NHL adds nothing.
Trimmed copies are in `app/src/test/resources/sports/*-real-20261010c.json`
(`SportsRealDataNightTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| Soccer scoreboard (live) | `STATUS_FIRST_HALF`, `period` 1, `displayClock`/`detail`/`shortDetail` `41'`, `clock` 2460.0 (elapsed seconds). No `situation`, no `linescores`. `details[]` cards carry `yellowCard`/`redCard` flags, `team.id`, `clock.displayValue` and no `period`; one 39' Leeds yellow has no `athletesInvolved` and is not in the summary, its key events, commentary or the team's card count (a card to staff). Competitors carry live `statistics` (`possessionPct`, `totalShots`, `shotsOnTarget`, `wonCorners`, `foulsCommitted`) and `form`. | Football cards without a player are left out (strip markers and red-card counts follow the players' cards only) |
| Soccer summary (live) | `keyEvents[]` include `Kickoff`, `Start Delay`/`End Delay` (injury) and cards; each has `type.type` slug (`yellow-card`), `period.number`, `clock.displayValue`, `team.id`, `participants[].athlete`, `shortText` and an exact `wallclock`. No root `plays`; `commentary[]` (56) is in time order with `time.displayValue` and a nested `play`. Header `linescores` hold one entry (`0`) in the first half, no `period`. Box score has `possessionPct` `58.4` and fractions for `shotPct`/`passPct` (`0.3`). Rosters: `formation` (`4-2-3-1`), `starter`, `subbedIn`/`subbedOut` false for all, bench `position.abbreviation` `SUB`, per-player `plays[]` with `yellowCard`/`redCard`/`substitution`. Leaders' `mainStat.label` is an ESPN code (`SHOT`, `ACPASS`, `DINT`, `SV`). `odds`/`pickcenter` (moneyline incl. draw) present, `winprobability` absent. `meta.firstPlayWallClock` is the actual kick-off (11:30:46Z). | Bench `SUB` position dropped; formation (`SummaryRoster.formation`) and booked/sent off (`SummaryPlayer.booked`/`sentOff`) kept; football leaders show the number only; cards without a player left out |
| NBL (live, 4th quarter) | Same shapes as the fifth pass; `plays[].text` can hold a line break (`bad pass\nturnover`, also NBA) or double spaces. At 4th 2:51 the game was 102 min past the scheduled tip-off (fifth pass: 2nd 5:56 at 37 min). | Play, moment and last-play texts collapse whitespace; NBL markers use 23 real minutes per quarter (was 27; WNBA unchanged), 4th 2:51 now 104 min |
| Tennis, golf, others | Live tennis as in the fifth pass (server only with `situation`); golf still between rounds; other leagues `pre`/`post` with the shapes above. | none |

Confirmed now: football live status, clock, period, score, cards (scoreboard and summary,
with side, minute and wallclock), live team stats, line-ups with formation, commentary as
last plays; NBL late-game clock and the marker pace.

Still unconfirmed: football goals, own goals, penalties, substitutions (`subbedIn`/`subbedOut`
and the key event), red cards, stoppage-time clocks (`45'+2'`), half-time and second-half
status names (`STATUS_HALFTIME`, `STATUS_SECOND_HALF`) and the second header line score;
plus everything still listed under the fifth pass (NFL/college `situation`, MLB count and
runners, NBA live win probability, NHL running clock and power play, F1/NASCAR live order,
UFC live round, cricket live innings, AFL/NRL/URC live clock, golf with the field on the
course and after a cut).

## TheSportsDB real data (10 October)

Livescore returned games only for Soccer (188), Basketball (122), Ice_Hockey (96), Baseball (3)
and American_Football (1); all other sports, including Rugby, Cricket and Australian_Football,
answer `{"Message":"No data found"}`. Each item has `idEvent`, team ids and names, badges, string
scores, `strStatus` (1H, 2H, HT, FT, P, NS, Q1–Q4, P1–P3, OT, BT, POST, CANC, or null),
`strProgress` (minute such as `45+1`, `Final`/`FT`), `strTimestamp` (UTC) and `updated`; no
clock or stats. Paths are case-sensitive in practice (`Soccer`, `Ice_Hockey`,
`American_Football`). `lookuptv` (`tvevent`) returned channels for 2 of 10 events (46 for the
NFL game, in 22 countries) with `strChannel`, `strCountry`, `strEventCountry`, `strLogo`,
`strTimeStamp`. v1 `all_leagues` (`leagues`) and v2 `all/leagues` (`all`) match: 1,547
leagues, 38 of them placeholders. `eventsday` for `English_Premier_League` returned 6 events
with live `strStatus` but no `strProgress` and no `strTVStation`; `Australian_AFL` returned
null (off-season).

## Seventh pass (11 October)

Real responses captured about 05:07 Brisbane time on 11 October (19:07Z on 10 October),
TheSportsDB calls three minutes later. In play: five college football games (four in the
4th quarter, one early in the 3rd), one NHL game (Boston v Philadelphia, 3rd period, a
minor penalty as the last play) and a URC match in the first half (Leinster v Cardiff,
15–0). Six Premier League games had finished (goals, substitutions, two straight reds, stoppage
time up to `90'+8'`). PGA was again between rounds (round 3 complete), no tennis was live,
F1 qualifying was over, NASCAR and UFC were upcoming. `epl-summary-LIVE.json`,
`nbl-summary-LIVE.json`, `ncaaf-summary.json`, `nhl-summary.json` and
`rugby-urc-summary.json` are byte-for-byte the sixth-pass files. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261011.json` (`SportsRealDataWeekendTest`,
`SportsDbExtrasTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| College football scoreboard (live) | `situation` has `down`, `distance`, `yardLine`, `downDistanceText` (`3rd & 4 at NEB 47`), `shortDownDistanceText`, `possessionText`, `isRedZone`, `homeTimeouts`/`awayTimeouts` and `possession` (team id); `lastPlay` has `probability.homeWinPercentage` (0.6003), `drive` and `start`/`end` yard lines. College play texts start with the snap clock, `(08:05) No Huddle-Shotgun …` (131 of 140 plays; NFL texts never do). | Snap clock removed from the last play (scoreboard and summary) |
| College football summary (live) | No root `plays` or `situation`: `drives.previous` plus `drives.current` (plays with `wallclock`), `scoringPlays` (6), `winprobability` (135, last entry matches the scoreboard), team box score. Wallclocks: kick-off 8 min after the listed start, quarters 39–44 real minutes, half-time 21 min, 4th 7:43 left at 187 min. | College football markers use 45 real minutes per quarter and a 20-minute half-time (NFL shape put the same moment at 156 min) |
| NHL (live) | Scoreboard `situation` holds only `lastPlay` (the penalty). Summary plays: penalties carry `type.penaltyMinutes` (`"2"`) and `type.penaltyType` (`Minor`) and their own `strength` is the state before the call (`Even Strength`); the following plays switch to `Power Play`/`Shorthanded`. Stoppages have no team. `onIce` lists skaters by id only. Boston led 3–0 at 3rd 13:44 (6:16 elapsed in the plays), 127 min after the listed start; puck drop was 8 min late. | Strength read from the last play with a team; a minor or major penalty as the latest such play gives the other side the power play (not for coincidental penalties or misconducts) |
| URC scoreboard (live) | `STATUS_FIRST_HALF`, `period` 1, `clock` 0.0 and `displayClock`/`detail` `1'` while `details` already reached 19' (three tries, a yellow card); every finished URC game also shows `1'`. Live `linescores` are all 0 (half lines, 20' and 60'). Finished games give per-half values in periods 1 and 2 (`29`, `28` for 57) with 20'/60' as running totals. | A minute clock behind the latest event minute is dropped and the status description (`First Half`) shown instead; all-zero period lines with a non-zero score are left out for both sides |
| URC summary (live) | Header `displayClock` `1'`, `linescores` without `period`: `15, 0, 15, 15` (halves, then the 20' and 60' totals); details use `participants[].athlete`. | Union summary lines keep the two halves only and, while live, only the halves played (`15`) |
| Football (finals) | `keyEvents` substitutions: `participants` = [on, off], `shortText` names only the player coming on, `text` `Substitution, Arsenal. Jurriën Timber replaces Ben White.`. Goals: `participants` = [scorer, assister]. `Halftime` key event at `45'+3'`. Scoreboard `details` clocks include `45'+3'` and `90'+5'`; straight reds are `type.id` 93 with `redCard` true. | Substitution moments use the full text (both players) |
| Tennis | `STATUS_WALKOVER` with `timeValid: false` dated two days ahead (`04:00Z` placeholder) listed as finished; `STATUS_RETIRED` and `STATUS_CANCELED` as before. | A finished match with a placeholder date in the future is left out |
| Pre-game summaries | NBA preseason `boxscore` holds `Streak`, `Points Against` (`129.0`), `Last Ten Games`; Champions League holds season totals. | none (season values stay, as decided in the fourth pass) |
| Others | AFL, NRL, A-League, NBL, NFL, MLB, NBA, UFC, F1, NASCAR, cricket and golf with the shapes above. | none |

TheSportsDB at 19:10Z: livescore returned Soccer (105), Basketball (60), Ice_Hockey (71) and
one American_Football row (an NFL final from 9 October); every other sport, tennis, rugby,
cricket, AFL and motorsport included, answered `{"Message":"No data found"}`. New statuses:
`AOT` (hockey and basketball), `ABD`, `BT`, `OT`; basketball `HT` with progress `0`. Hockey
and basketball `strProgress` is the minute played in the period (`P2` `17`, `Q4` `9`, `FT`
`10`), not a countdown. `updated` is UK local time (latest `20:08:33` at 19:10Z); three
hockey rows (QMJHL, WHL, AHL) were stuck at `P3 20` or `OT 4` with `updated` 13–15 hours
old. `lookuptv` returned channels for 4 of 21 events (NFL 46, Monaco v Toulouse 9, a
Bolivian match 1, a Turkish basketball game 2); new countries include `Bosnia and
Herzegovina`, `Qatar`, `Saudi Arabia`. Parser changes: rows whose `updated` is more than 3
hours old no longer keep a game live; minute clocks (`17'`) count up on the timeline; channel
countries match names written with "and" or "Saint" (`Bosnia & Herzegovina`, `St. Kitts &
Nevis` in Java's list) plus Hong Kong, Macau, Macedonia, DR Congo and Ivory Coast.

ESPN league lists (`{sport}/leagues`) are unchanged since 8 October. Added to the app's list
(off by default), all with the parser of a league already checked against real data:
Ligue 1, Europa League, Conference League, EFL Championship, FA Cup, EFL Cup, Scottish
Premiership, Eredivisie, Primeira Liga, Süper Lig, Saudi Pro League, MLS, Liga MX, Women's
Super League, NWSL, Women's Champions League, NBA G League, women's college basketball (4×10
minutes), CFL, UFL, men's and women's college hockey, PFL, LPGA Tour, DP World Tour, LIV Golf,
NASCAR Xfinity and Truck Series, IndyCar, Premiership Rugby (267979), Top 14 (270559),
Champions Cup (271937), Six Nations (180659) and The Rugby Championship (244293). The rugby
names are not in the capture (the list gives ids only); check them on the device.

Not added: men's college basketball (two 20-minute halves; the timeline only knows
quarters), the other 200 or so football competitions (lower divisions, cups, youth,
qualifiers, friendlies), baseball's college and winter leagues and international events,
golf's Champions and Korn Ferry tours, TGL and Olympic events, the other 46 MMA promotions
(mostly historical), the other 20 rugby union ids (names unknown), FIBA, summer leagues,
lacrosse, volleyball, water polo and field hockey (no parser checked against their data).

Confirmed now: college football live `situation`, possession, down and distance, win
probability and drives; NHL live penalty and power play shape; URC live status and details;
football goals, substitutions, stoppage-time clocks and straight red cards;
TheSportsDB live hockey and basketball progress.

Still unconfirmed: NFL live `situation`; MLB balls/strikes/outs and runners; NBA live win
probability; tennis serve in a live match tiebreak; F1/NASCAR live order and gaps; UFC live
round, method and winner; cricket live innings; AFL/NRL live clock; golf with the field on
the course and after a cut; football own goals, penalties, second yellows and the
half-time and second-half status names on the scoreboard.

## Eighth pass (11 October, morning)

Real responses captured about 10:35 Brisbane time on 11 October (00:35Z), TheSportsDB calls
in the same minute. In play: the first live MLB game seen (Cleveland v Chicago White Sox, end
of the 2nd, 0–0), five NBA preseason games (two in the 1st quarter, three in the 3rd), four
college football games, nine NHL games (eight in the 2nd period, one in the 3rd), the PGA
final round with 36 of 72 players on the course, and a UFC card at the walkouts for the first
main-card bout. `epl-summary-LIVE.json`, `nbl-summary-LIVE.json` and
`rugby-urc-summary-LIVE.json` are byte-for-byte the earlier files, so there is no new live
football, NBL or rugby; the Premier League, NFL, NHL final, NBL final, AFL and NRL summaries
are the same events as the seventh pass. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261011b.json` (`SportsRealDataSundayTest`,
`SportsDbExtrasTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| MLB scoreboard (live) | `STATUS_IN_PROGRESS`, `period` 2, `displayClock` `0:00`, `shortDetail` `End 2nd`. `situation` has `balls`, `strikes`, `outs` all reset to 0 between innings, `onFirst`…`onThird` false, `dueUp` (three batters with full athlete) and `lastPlay` (`End of the 2nd inning`). Linescores per inning with `period`; competitors carry `hits` and `errors` totals. | Between innings (`End`, `Mid`) no count, outs or runners are kept: the bug shows `End 2` without `0 outs · 0–0` |
| MLB summary (live) | Header `periodPrefix` `End`, linescores with `hits`/`errors` per inning, no `period`. Root `situation` as on the scoreboard but `dueUp` by `playerId` only, plus `situationNotes` (chance of scoring), no `pitcher`/`batter` between innings. `plays` (90) are pitch by pitch: `summaryType` `A` (now batting), `P` (each pitch, `Pitch 4 : Strike 3 Swinging`), `N` (at-bat result), then an untexted end row; `pitchCount` is the count before the pitch and `resultCount` after it; `outs` on every row is the total after the at-bat; `period.type` `Top`/`Bottom`/`End`; `participants` by id only (pitcher, batter). Batters are named in `rosters` (the line-up), pitchers only in `boxscore.players[].statistics[type=pitching]`. `atBats` and `playsMap` index the plays. Every play has `wallclock`; first pitch 9 min after the listed start, the first two innings 9 and 13 min. | Between innings the count is cleared; pitch rows are left out of the last plays (results and "now batting" stay); a situation player given by `playerId` is looked up by name |
| NBA scoreboard (live) | `displayClock` `1:53` and, under a minute, `24.7`/`13.3` (`clock` 24.7). `situation` holds only `lastPlay` with `probability.homeWinPercentage`; one free-throw `lastPlay` had no player or team (`misses free throw 2 of 2`). One game's 1st-quarter line read 23 while the score was 24 (line lags the score). | none — the lag is at source and clears with the next update |
| NBA summary (live) | Header competitors carry `timeoutsUsed`/`timeoutsRemaining` and `fouls` (`teamFouls`, `teamFoulsCurrent`, `foulsToGive`, `bonusState` `DOUBLE`); `possession` false for both. 366 `plays` with `wallclock`, `scoringPlay`, `shortDescription`; `winprobability` 366 entries. Tip-off was 13 min after the listed start; quarters 28–37 real minutes, half-time 16. | none (fouls and timeouts are not shown; markers for plays use the wallclock) |
| College football scoreboard (live) | During a timeout `situation` drops `downDistanceText`, `possessionText` and `possession` (`down` 0). At midfield `possessionText` is `50` with no team; `downDistanceText` `4th & 10 at 50`. Snap-clock prefix again on most `lastPlay` texts. | A spot without a team uses the source text (`4th & 10 at 50`, was `4th & 10 · 50`) |
| College football summary (live) | Same shape as the seventh pass. Kick-off 8.5 min late; 2nd 0:32 at 94 min after the listed start (timeline shape 92). | none |
| NHL (live) | Scoreboard `situation` only `lastPlay`. Summary: at one stoppage four roughing minors (two each) were followed by a holding minor; the penalty plays carry `Power Play`/`Even Strength` inconsistently, so the last roughing minor read as a home power play for the four minutes of the TV timeout. Puck drop 8.5 min late, periods 38 real minutes, intermissions 19. | Penalties at the same stoppage are counted per side: equal numbers are even strength, an extra minor gives the other side the power play |
| Golf (final round) | 36 of 72 players on the course: hole lines and today's to-par on the round line; the leaders, yet to start, have tee times only. Still no per-player `status`. | none |
| UFC (walkouts) | Card `STATUS_IN_PROGRESS`; the bout at the walkouts is `STATUS_PRE_FIGHT` with state `in`, `period` 0, `displayClock` `-`. Finished bouts keep the finishing round and time (`period` 1, `2:59`) and `details` such as `Unofficial Winner Kotko` or `Unofficial Winner Submission`; a draw has no winner. | none (method and finishing time are not stored) |
| URC | No live game. New final (Bulls 57–15 Lions): halves 29/28 and 10/5, 20'/60' totals 5/36 and 7/15. | none |

TheSportsDB at 00:35Z: livescore returned Soccer (36), Basketball (114), Ice_Hockey (127) and
Baseball (4, all KBO and NPB not started); no MLB, and every other sport answered
`{"Message":"No data found"}`. The NBA rows agree with ESPN: `Q3` `10` (ESPN 3rd 2:12),
`Q1` `11` (ESPN 13.3 left); one game was still `HT` `0` after ESPN had restarted the 3rd, and
one not yet started was `NS` `0`. NHL `P3` `13` matched ESPN's 3rd 6:07. The WHL and AHL rows
stuck since 9 October (`P3 20`, `OT 4`, `updated` 20 hours old) are still there and are
skipped. A Guatemalan match sat at `1H` `45+16` with `updated` 29 minutes old (a long delay;
still shown as live). `lookuptv` returned channels for 6 of 29 events; new is
`strCountry` `World` (NBA League Pass, Fanatiz INT). Parser change: worldwide channels are
listed after the viewer's own country's channels instead of being dropped.

Checked again on this data: college football clocks without the snap prefix and the
45-minute quarter shape, NHL power plays, TheSportsDB stale rows and count-up clocks; the
rugby clock, substitution text and URC live summary had no new live data.

ESPN league lists are unchanged. Added to the app's list (off by default), all football
competitions on the soccer parser already checked live: AFC Champions League Elite, AFC
Asian Cup, UEFA Nations League, Copa Libertadores, EFL League One and League Two, Copa del
Rey, DFB-Pokal, Coppa Italia, Coupe de France, Belgian Pro League, Brasileirão, Argentine
Primera División and J1 League. Still not added: men's college basketball (needs two halves
in the Game Centre strip and `1H`/`2H` labels, which are screen changes), FIBA (10-minute
quarters, no data seen), baseball's college, winter and international leagues (no data
seen), XFL (now part of the UFL), golf's Champions and Korn Ferry tours, TGL and Olympic
events, the other MMA promotions, the unnamed rugby union ids, lacrosse, volleyball, water
polo, field hockey and the remaining football competitions.

Confirmed now: MLB live status, inning half, count reset between innings, pitch-by-pitch
plays, wallclocks and win probability; NBA live clock under a minute, live win probability,
fouls and timeouts in the summary header; college football timeouts and midfield; NHL
coincidental minors; golf with the field on the course; UFC walkouts and finished bouts.

Still unconfirmed: MLB count, runners, pitcher and batter during an at-bat (only seen
between innings); NFL live `situation`; tennis serve in a match tiebreak; F1/NASCAR live
order and gaps; UFC round in progress; cricket live innings; AFL/NRL live clock; golf after
a cut; football own goals, penalties, second yellows and the half-time and second-half
status names on the scoreboard.

## Ninth pass (11 October, 11:03)

ESPN responses captured at 11:03 Brisbane time on 11 October (01:03Z), 28 minutes after the
eighth pass; no TheSportsDB calls. In play: the same MLB game (Cleveland v Chicago White Sox,
bottom of the 4th, 0–0, no outs, a runner on first), six NBA preseason games (one in the 4th,
one at the end of the 3rd, one in the 3rd, two in the 2nd, one in the 1st), four college
football games (one at half-time, three in the 2nd quarter), eight NHL games (five at the
second intermission, two at 20:00 of the 3rd, one in the 2nd), the PGA final round and the
UFC card at the walkouts of the next main-card bout. The Premier League, NBL and URC live
files are byte-for-byte the earlier ones. No pitching change, NBA overtime, red zone,
overtime, empty net or shootout was on. Trimmed copies are in
`app/src/test/resources/sports/espn-*-real-20261011c.json` (`SportsRealDataLateMorningTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| MLB scoreboard (live, at-bat) | `shortDetail` `Bot 4th`, `displayClock` `0:00`. `situation` `balls`/`strikes`/`outs` 0, `onFirst` true, `pitcher` and `batter` with full athlete, `period` and a `summary` line (`3.0 IP, 0 ER, H, 2 K, 0 BB`, `0-1`); `lastPlay` the new at-bat (`summaryType` `A`, `Sean Burke pitches to Jose Ramirez`). | none |
| MLB summary (live, at-bat) | Fetched just before the scoreboard (last update 01:02:09Z): the plays end on Kwan's single (`N`) and the untexted `end-batterpitcher` row, but the root `situation` still holds that finished at-bat: `strikes` 1, `batter.playerId` Kwan while `onFirst.playerId` is also Kwan. Runners on play rows are `onFirst: {athlete: {id}}` plus an `onFirst` participant. New pitch types: `bunted-foul` (alternative text `Out`), `single` as the in-play pitch. `situationNotes` now `BVP_STATS` (batter v pitcher). Half-innings 8–14 real minutes; bottom of the 4th began 61 min after the listed start (shape 58). | Once the at-bat's end row is the last play and the situation still names that batter, the count reads 0–0 with no batter (runners and outs kept); a situation already on the next batter is used as given |
| NBA scoreboard (live) | `STATUS_END_PERIOD` with `displayClock` `0.0` (the sub-minute format), `shortDetail` `End of 3rd`, `lastPlay` `End of the 3rd Quarter`. Two more free-throw `lastPlay`s with no player or team. | A zero clock in any form (`0.0`, `00:00`) gives the status text: `End of 3rd`, was `Q3 · 0.0` |
| NBA summary (live, 4th) | Same shape as the eighth pass with four quarters on the line. Quarters 28, 37 and 33 real minutes, half-time 16; 5:55 of the 4th at 152 min after the listed start (shape 135). | none (play markers use the wallclock) |
| College football scoreboard (half-time) | `STATUS_HALFTIME`, `displayClock` `0:00`; `situation` `down` and `distance` -1, `yardLine` 65, no texts or possession; `lastPlay` the conversion, text `(A. Zouagui KICK)` with type `Extra Point Good`. Elsewhere `isRedZone` false (one drive at its own 18). | A last play wholly in brackets is led by its type: `Extra Point Good (A. Zouagui KICK)` |
| College football summary (half-time) | `drives.current` repeats the last `previous` drive. The touchdown's play text runs past 240 characters (the score, an automatic review, an LSU timeout and the kick with holder and snapper); `scoringPlays` has the short form (`K. Minchey run for 1 yd, for a TD (A. Zouagui KICK)`). The 2nd quarter took 53 real minutes; half-time began 104 min after the listed start (shape 94). | Last plays use the scoring summary's text for a scoring play |
| NHL scoreboard | Second intermission `STATUS_END_PERIOD` `End of 2nd`, `0:00`. Start of the 3rd is `STATUS_IN_PROGRESS` `20:00 - 3rd` while `lastPlay` is still `End of 2nd Period`. | none |
| NHL summary (intermission) | Fighting majors (5 min each side) at 19:27 of the 2nd, one tagged `Even Strength`, the other `Power Play`; a goalie's 10-minute misconduct served by a skater; a delay-of-game minor followed by `Shorthanded`/`Power Play` rows and a power-play goal. Play clocks count up (`End of 2nd Period` at 20:00). The 2nd period took 45 real minutes (shape 88 min to its end, real 105). | none: the per-side count from the eighth pass reads the fighting majors as even strength |
| UFC (walkouts) | New bout status `STATUS_FIGHTERS_WALKING`: state `in`, `period` 0, `displayClock` `-`, `shortDetail` `Walkouts`, `details` `Fight Open`. The bout before it finished in round 2 at 0:22. | Until a round starts the bug shows the bout's status (`Walkouts`, `Pre-fight`) instead of `Round 1 of 3` |
| Golf | Leaders tee off at 01:21Z; a player who teed off four minutes before the capture had no `thru` yet. | none |

Checked again on this data: the between-innings rule (`Mid` rows and the `periodPrefix`),
pitch rows left out of the last plays, a situation player given by `playerId`, NBA clocks
under a minute and win probability, college football spots and snap-clock prefixes, NHL
penalties counted per side.

Confirmed now: MLB count, runners, outs and pitcher during an inning; NBA end of quarter and
the 4th quarter; college football half-time on the scoreboard and summary; NHL fighting
majors, misconduct and the start of a period at 20:00; UFC walkouts status.

Still unconfirmed: MLB pitching change, runners on second or third and a full count; NBA
overtime; college football red zone and overtime; NHL empty net and shootout; men's college
basketball (not in the capture); NFL live `situation`; tennis serve in a match tiebreak;
F1/NASCAR live order and gaps; UFC round in progress; cricket live innings; AFL/NRL live
clock; golf after a cut; football own goals, penalties, second yellows and the half-time
and second-half status names on the scoreboard.

## Tenth pass (11 October, 18:36)

ESPN and TheSportsDB responses captured at 18:36 Brisbane time on 11 October (08:36Z). In
play: five tennis matches, the first real live tennis seen (the WTA Suzhou final in the 2nd
set, two Mallorca qualifying matches, and a singles and a doubles match in Shanghai). Nothing
else was live: the `*-LIVE.json` summaries are byte-for-byte the ninth pass. Finished: the
UFC card, the PGA final round, Thursday's NFL game, 19 college football games, 14 NHL games
(three in a shootout, one in overtime), the MLB game, six URC games, two NBL games, the AFL
and NRL games and the cricket match. Before the start: 14 NFL games, the F1 race, NASCAR,
the NBA, A-League, Premier League and Champions League. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261011d.json` (`SportsRealDataSundayEveningTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| Tennis scoreboard (live) | `STATUS_IN_PROGRESS`, `period` the set number, `detail` `2nd Set`, `shortDetail` `2nd`. The open set's linescore has `value` and no `winner`; a new set reads `0` until a game is won. `possession` true on the server; one match had no `possession` at all. No point score and no live tiebreak; a finished tiebreak set carries `tiebreak` on both sides (7 and 2). The `notes` line is written from the first-listed player's side and is often wrong (`Shinikova leads Kucmova 1-6 0-0`). Doubles have `roster` names and no flags. | none (sets, games, server and tiebreaks already read as expected; `notes` is not used) |
| UFC (card finished) | Card and every bout `STATUS_FINAL`. Each bout keeps the finishing round in `status.period` and the time in `displayClock` (`5`, `2:46`; a decision `3`, `5:00`). The method is a `details` row `Unofficial Winner Submission` (type 20), `Unofficial Winner Kotko` (21, KO/TKO) or `Unofficial Winner Decision` (22), not always the first row. The draw has no winner and no such row. | Method, finishing round and time are stored per bout (draw when no winner after the full distance); the bug reads `B. Allen · Submission · R5 2:46` |
| Golf (final round finished) | Competition `STATUS_FINAL`, `detail` `Final`, `shortDetail` `Complete`; the event status has no detail. Still no per-player `status`; winner one shot clear (no playoff), ties only by equal scores. | none |
| F1 | Event status `STATUS_FINAL` (`period` 28) while the race session is `STATUS_SCHEDULED` for 12:00Z; race competitors have no `order` before the start. | none (the parser follows the sessions); covered by a test |
| NFL (before kick-off) | `shortDetail` `10/11 - 9:30 AM EDT`; `score` `0` on both sides before the start; records `total`, `home` and `road`; odds rows (not used). The London game is `neutralSite` with a `NFL London Games` note and the Jaguars as home. Thursday's final as before. | none |
| NHL (finals) | `Final/SO` with `altDetail` `SO`: five linescores, the shootout as period 5 worth one goal to the winner. `Final/OT` with four. `featuredAthletes` names the winning and losing goalie and the three stars. | The line-score column after overtime is `SO` for a shootout final (`periodLabel(fixture, period)`); later overtimes are numbered (`2OT`, `3OT`) for every sport |
| College football | One `Final/OT` with five periods. | none |
| URC (finals) | Leinster v Cardiff has linescore period 1 at `0` for both sides (the 20'/60' rows too) although Leinster scored tries at 9', 17' and 19', so the halves read 0/29 and 0/5. Other games as in the eighth pass. | A 0–0 first half is dropped when a score is listed before 40' (45' in football); only totals show |
| Cricket | Still the 2015 match. The scoreboard and summary status carry `summary` `Australia won by 112 runs` and `featuredAthletes` (player of the match and of the series). | The result is kept (`SportsDetail.Cricket.result`, `SportsSummary.result`) and becomes the bug's extra line after the match; summary awards are read |
| MLB summary (final) | Header `featuredAthletes`: winning, losing and saving pitcher. The root `situation` still holds the last count (3 outs, pitcher) after the final out; plays end with `pitches to` rows between the results. | No count once the game is over; `pitches to` rows are kept only as the newest play; awards are read |
| NHL, NBL, AFL, NRL, college football summaries (finals) | Same shapes as earlier passes (NRL 19–18 with a field goal in regulation time). NHL summary `featuredAthletes` as on the scoreboard. | Awards read |

TheSportsDB at 08:36Z: livescore returned Soccer (31), Basketball (104), Ice Hockey (77) and
Baseball (4, all KBO and NPB finished); every other sport, including tennis, rugby, cricket,
Australian rules and American football, answered `{"Message":"No data found"}`. `updated` is
London time (09:36 = 08:36Z). The NBL `FT` row agrees with ESPN (100–110); finished
basketball rows keep `strProgress` `10`; one NPB final has `intHomeScore` null; a Taiwanese
match sat at `HT` `45+4`. The WHL and AHL rows stuck since 9 October are still there and
skipped. New: an AHL and a USHL game that started at 22:00Z were still `P3` `20` and `P3`
`19` with `updated` refreshed every minute (the USHL row read `P3 20` at 00:35Z), so the
staleness check let them through. Parser change: a live row is ignored once the game started
more than three game lengths ago (at least three hours). `lookuptv` returned channels for 6
of 36 events; every country named resolved (United Kingdom, United States, Bosnia and
Herzegovina, Czechia, The Netherlands, Turkey); Thursday's NFL game lists five state feeds of
7mate for Australia, all shown.

ESPN league lists are unchanged from the eighth pass. Leagues the current parsers can read
but the app does not offer: second divisions in Germany, Spain, Italy and France, the
Austrian, Greek, Russian and Chinese top flights, Liga de Expansión MX, USL Championship and
League One, CONCACAF Champions Cup and Leagues Cup, the UEFA Super Cup, international
friendlies, World Cup and continental qualifiers, the Euros and Copa América, Liga F,
Première Ligue, Liga MX Femenil, the UEFA Women's Nations League and the women's FA Cup
(football parser); the Champions Tour (golf leaderboard); and the MMA promotions on the card
parser (Bellator, KSW, Cage Warriors and others). Men's college basketball still needs halves
on screen; lacrosse, volleyball, water polo and field hockey have no parser.

Checked again on this data: tennis retirements and walkovers, doubles match tiebreaks, the UFC
card once every bout is over, stale TheSportsDB rows by `updated`, worldwide TV channels.

Confirmed now: live tennis sets, games and server; finished UFC cards with method, round and
time; a finished golf final round; NHL shootout and overtime finals; college football
overtime final; NFL before kick-off; cricket result text and player of the match.

Still unconfirmed: tennis point score and a live tiebreak (not in ESPN's scoreboard at all
so far); MLB pitching change, runners on second or third and a full count; NBA overtime;
college football red zone; NHL empty net; NFL live `situation`; F1/NASCAR live order and
gaps; UFC round in progress; cricket live innings; AFL/NRL live clock; golf after a cut or a
playoff; football own goals, penalties, second yellows and the half-time and second-half
status names on the scoreboard.

## Eleventh pass (11 October, 22:46)

ESPN and TheSportsDB responses captured at 22:46 Brisbane time on 11 October (12:46Z). In
play: the Singapore Grand Prix, the first live motor race seen (lap 26, 46 minutes after the
listed 12:00Z start), and eight WTA matches (one in the 3rd set, five in the 2nd, two in the
1st). Nothing else on ESPN was live: the `*-LIVE.json` summaries are the ninth-pass files, and
the AFL, A-League, cricket, MLB, NASCAR, NRL, PGA, URC and UFC scoreboards are byte-for-byte
the tenth pass. The ATP, Premier League, NBA, NBL, college football, NFL, NHL and Champions
League scoreboards changed only by status (the NBA preseason game finished, Rockets 115–132
Mavericks); parsed, every other fixture is identical to the tenth pass. The AFL, A-League,
NBL, college football, NFL, NHL, NRL, URC, MLB and Champions League summaries parse exactly
as before. Trimmed copies are in `app/src/test/resources/sports/*-real-20261011e.json`
(`SportsRealDataRaceNightTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| F1 race (live) | Race competition `STATUS_IN_PROGRESS`, `detail` and `shortDetail` `In Progress`, `displayClock` `0:00`, `period` 26 (the lap). The event status still says `STATUS_FINAL` (`period` 28) for the whole weekend. All 22 competitors carry `order` 1–22, updated live (Piastri 7th on the grid, now 3rd; Russell 6th, now 11th); `winner` false for all and `statistics` empty. No gaps, intervals, pit stops, retirements, fastest lap or race distance anywhere in the scoreboard; the multi-sport header has no racing. Finished sessions also carry a `period` (28, 15, 20, 15), not used. | A live race or sprint keeps the lap and the top three of the running order (`RaceSession.lap`, `top`, also in the cache); the bug reads `Race · Lap 26` with `1 M. Verstappen · 2 L. Norris · 3 O. Piastri` in place of the circuit |
| Tennis (live) | Same shapes as the tenth pass, now with a match in the 3rd set (`period` 3, `shortDetail` `3rd`). Where `possession` is set the competition also has a `situation` with baseball fields only (`onFirst`, `onSecond`, `onThird`, all false). A China Open women's match is in the ATP scoreboard too, under `womens-singles`. Still no point score; `notes` still written from the first-listed side. | none (the stray situation is ignored; the ATP list keeps the men's groupings) |
| Football summary (before kick-off) | Crystal Palace v Nottingham Forest 14 minutes before kick-off: `rosters` with `formation` `3-4-2-1`, 11 starters with `formationPlace` 1–11 and 9 substitutes (`formationPlace` 0, position `SUB`) per side. | none (the Line-ups tab fills before kick-off) |
| NBA summary (final) | Four quarters, 400 win-probability rows ending at 0 for the home side, `End of Game` as the last play; no `featuredAthletes`. | none |

TheSportsDB at 12:46Z: livescore returned Soccer (212), Basketball (94) and Ice Hockey (75);
every other sport answered `{"Message":"No data found"}`. Football minutes track the real
clock (`16` at 16 minutes, `45+1` at 46, `2H` `87` at 106), stoppage time up to `90+10` on
finished rows, half-time rows keep the stoppage minute (`HT` `45+1`); no extra time or
penalties were live. Basketball `Q2`/`Q4` progress is the minute of the quarter; finished
rows have `10`, `12` or no progress; a Czech game due at 15:00Z was `POST` at 0–0 (skipped).
A Ukrainian SuperLeague row had `strStatus` null with 37–45 at 166 minutes after the start (a
game from the same league sat at 41–46 the day before); earlier in the day it was `NS`. Six
hockey rows were `BT` (break, no progress) 46 minutes after a 12:00Z start, after the 1st
period. The AHL and USHL rows that started at 22:00Z on 10 October were still `P3` `20` and
`P3` `19`, refreshed every minute, and the WHL `P3` `20` and AHL `OT` `4` rows from 10 October
were still there; all skipped by the rules of the earlier passes. `lookuptv` returned channels for
9 of 46 events (the NFL game 46, Liverpool v Manchester City 43 including one for Australia,
Monaco v Toulouse 9, single channels marked `World`); the new countries are plain English
names. ESPN and TheSportsDB league lists are unchanged from the tenth pass.

Parser changes: a TheSportsDB row with no status no longer keeps a game live once the
game length has passed; `OT` sets the period (4th in hockey, 5th in basketball and American
football), so the clock reads `OT · 4'`; the football bug shows `HT` during the break
instead of the frozen stoppage minute.

Checked again on this data: stale and stuck TheSportsDB rows, count-up minutes, the race
window (live until the end of the race, at least 150 minutes after its start), tennis
server and sets, worldwide channels.

Confirmed now: F1 live race status, lap and running order; live tennis in a deciding set;
football line-ups before kick-off; TheSportsDB hockey breaks and rows without a status.

Still unconfirmed: F1 gaps, pit stops, retirements and fastest lap (not in ESPN's scoreboard
at all so far), the race's finished state and NASCAR live; tennis point score and a live
tiebreak; MLB pitching change, runners on second or third and a full count; NBA overtime;
college football red zone; NHL empty net; NFL live `situation`; UFC round in progress;
cricket live innings; AFL/NRL live clock; golf after a cut or a playoff; football own goals,
penalties, second yellows, extra time and the half-time and second-half status names on
ESPN's scoreboard.

## Twelfth pass (12 October, 05:46)

ESPN and TheSportsDB responses captured at 05:46 Brisbane time on Monday 12 October
(19:46Z on 11 October). In play: the first live NFL seen (eight 1pm games, all in the 4th
quarter between 10:49 and 2:45 to go; Eagles 17–19 Jaguars final from the London game; five
games still to start) and the NASCAR Cup race at Charlotte (lap 41, 46 minutes after the listed
19:00Z start). The Singapore Grand Prix is now final. Everything else on ESPN was before or after
play: Premier League, AFL, NRL, NBA, NBL, college football, cricket and UFC finals; MLB, NHL,
URC, A-League and Champions League still to start. Apart from the NFL file, the `*-LIVE.json`
summaries are earlier-pass files, and `nfl-summary.json` is the 11 October pre-game file. Every
scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261012.json` (`SportsRealDataMondayTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NFL `situation` (live) | `down`, `distance`, `yardLine` (0–100 from the home goal line), `downDistanceText` `3rd & 14 at CHI 37`, `shortDownDistanceText`, `possessionText` `CHI 37`, `isRedZone` (false in every game), `homeTimeouts`/`awayTimeouts` (0–3), `possession` (team id) and `lastPlay` with `type`, `text`, `team`, `start`/`end` (yard line and team), `probability` (0–1) and `drive` (`description` `4 plays, 20 yards, 2:00`, `start`, `timeElapsed`, `result` once the drive is over). After an extra point there is no down text and `possession` is the kicking side; after a kick-off return the Jets game had down text but no `possession` at all. | `FixtureSituation` keeps the red-zone flag, both teams' timeouts and the drive in progress (also in the cache); possession falls back to the team at the end of the last play when the down text is there; the drive is left out once it has a result or has no plays yet |
| NFL last play text | Leading space, line breaks inside penalty texts, formation tags `(Shotgun)`, `(No Huddle, Shotgun)`; `Official Timeout at 04:34.`; an extra point reads `(Matt Gay Kick)` with type `Extra Point Good` | Formation tags (and college `No Huddle-Shotgun`, `Shotgun` before a shirt number) are dropped from the last play on the scoreboard and in the Game Centre |
| NFL clock | `displayClock` `6:22`, `period` 4, `shortDetail` `6:22 - 4th`; no end-of-quarter, half-time or two-minute status live on the scoreboard at capture time | none (`Q4 · 6:22`; the bug extra is the down and spot) |
| NFL summary (live) | No `plays` and no `situation`: 21 `drives.previous` (the current drive repeated last) plus `drives.current`, 153 plays with `wallclock`, types including `Penalty`, `Sack`, `Pass Interception Return`, `Fumble Recovery (Opponent)`, `Two-minute warning`, `End Period`, `End of Half`; drive results Punt, Touchdown, Interception, Missed FG, Fumble, Field Goal, End of Half. Six `scoringPlays` (no wallclock of their own, matched by id to the drive plays). Header competitors carry `possession` and `timeoutsUsed`. 154 win-probability rows; `odds` empty, `pickcenter` present | none: scoring plays get the real times, the line score, stats (turnovers 0–4), leaders and last three plays read correctly |
| NFL timing | First play 3 m 40 s after the listed start; Q1 ended at +29 min, Q2 at +79 (the two-minute warning alone took 20 minutes), half-time 15.6 minutes, Q3 ended at +138; the game clock estimate (40 minutes a quarter, 13-minute half-time) was 10–17 minutes early late in the first half and 4–7 minutes late in the 4th | none: every NFL scoring play has a real timestamp, so the estimate is only a fallback |
| Odds | `odds` only on the five games still to start; live and final games have none | none (not used) |
| NASCAR race (live) | No competition `type` (named Race by default), `period` 41 is the lap, 39 competitors with `order` 1–39 updated live (Cindric 5th on the grid, 2nd now), `winner` false, empty `statistics`; no stage, caution or lap-total field and no circuit or venue | none: lap 41 and the running order show as for F1 |
| F1 weekend (final) | Race `STATUS_FINAL`, `period` 62, `order` final with `winner` true on the winner | The bug shows the podium (`1 M. Verstappen · 2 C. Leclerc · 3 K. Antonelli`) instead of the circuit once the weekend is over |

TheSportsDB at 19:46Z: livescore returned Soccer (75), Ice Hockey (31), Basketball (16) and,
for the first time, American Football (9): NFL rows with `strStatus` null and `strProgress`
copied from ESPN's short detail (`3:08 - 4th`, refreshed every minute; six games matched ESPN's
clock, two were 23 and 43 seconds of game clock behind) or `Final`. A German DEL 2 row finished after a shoot-out was `AP`; a Czech
row `AOT`. Two German DEL rows sat at `P3` `20` and `P3` `19` with `updated` three hours old,
316 minutes after the start, while every other live row was refreshed at the capture minute.
Basketball half-time is `HT` with progress `0`. `lookuptv` returned channels for the NFL games
(12 to 46 rows: 7mate by state plus DAZN in Australia, NFL Sunday Ticket and Paramount+ in the
United States) and the football matches seen in the eleventh pass; most other events had none.

Parser changes: TheSportsDB `M:SS - Nth` (and `- OT`) progress sets the quarter and clock, so a
TheSportsDB NFL game reads `Q4 · 3:08`; other worded progress up to 24 characters becomes the
status text; `AP` counts as finished; a live row that has not been refreshed for 30 minutes after
the game length is ignored.

Checked again on this data: TheSportsDB `Final` rows with no status, worldwide and Australian
channels, F1 lap and running order logic on NASCAR, the race window.

Confirmed now: NFL live `situation`, timeouts, drive, possession and win probability; NFL
summary drives and real play times; NASCAR live lap and running order; F1 finished race;
TheSportsDB American football livescore.

Still unconfirmed: NFL red zone, half-time, end of quarter and overtime on the scoreboard
(college football showed `isRedZone` true during a timeout with no down text); F1 gaps, pit
stops and fastest lap; NASCAR stages; tennis point score and a live tiebreak; MLB pitching
change and a full count; NBA overtime; NHL empty net; UFC round in progress; cricket live
innings; AFL/NRL live clock; golf after a cut or a playoff; football own goals, penalties,
second yellows and extra time.

## Thirteenth pass (12 October, 06:42)

ESPN and TheSportsDB responses captured at 06:42 Brisbane time on Monday 12 October (20:42Z on
11 October), 56 minutes after the twelfth pass; TheSportsDB `updated` reads 21:42 London time.
In play: four NFL games (Giants at Commanders in overtime, 27–27 with 1:50 left; Broncos at
Chargers at the end of the 1st quarter; Lions at Cardinals and 49ers at Seahawks in the 1st)
and the NASCAR Cup race at Charlotte (lap 94). The other eight 1pm NFL games and the London game
were final; Premier League finals (Crystal Palace 3–1 Nottingham Forest, Hull 2–2 Everton,
Liverpool 0–3 Manchester City); finals for college football, the NBA preseason, NBL, AFL, NRL,
UFC, PGA, F1 and tennis (no tennis live); NHL, MLB, A-League, URC and Champions League still to
start. Only `nfl-summary-LIVE.json` is new; the other `*-LIVE.json` files are earlier-pass files,
`nfl-summary.json` is the 11 October pre-game file, and the multi-sport header was a cached copy
from about 05:00Z (every NFL game `pre`; the app does not use it). The `espn-pass.zip` inside the
upload is byte-for-byte the seventh-pass capture (05:07 Brisbane on 11 October) and adds nothing.
The TheSportsDB coverage zip was made at 20:42Z, but its `v2-livescore-app-*`, `v1-lookuptv-*`
and `v1-eventsday-*` files are the twelfth-pass files from 19:46Z (the folder is reused); the
calls zip holds the 20:42Z set. Every scoreboard and summary parses without errors. Trimmed copies
are in `app/src/test/resources/sports/*-real-20261012b.json` (`SportsRealDataOvertimeTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NFL overtime (live) | `STATUS_IN_PROGRESS`, `period` 5, `displayClock` `1:50`, `shortDetail` `1:50 - OT`; a fifth linescore (`0`) on both sides; `situation` as in regulation, with `homeTimeouts`/`awayTimeouts` 0 once both teams had used their two overtime timeouts. Summary header `displayPeriod` `5th`, `statusSecondary` `OT`, `timeoutsUsed` 2; regulation closed with an `End of Regulation` play 194 minutes after the listed start, the overtime kick-off came 4 minutes later and 8 minutes of game clock took 21 real minutes (a missed field goal, a punt, a lost fumble). Drives and scoring plays as in the twelfth pass. | none for the clock and line score (`OT · 1:50`, `6 6 7 8 0`, column `OT`); `SportsFixtureText.timeoutSlots` gives the number of timeout marks: 2 in NFL overtime, 1 in college overtime, otherwise 3 |
| NFL end of quarter | `STATUS_END_PERIOD`, `displayClock` `0:00`, `shortDetail` `End of 1st`; `situation` keeps the down and spot (`4th & 17 at LAC 41`); `lastPlay` type `End Period`, text `END QUARTER 1` | none (`End of 1st` with the down as the extra line) |
| NFL red zone | The first genuine one: `isRedZone` true at `2nd & 5 at SF 8`. Also true straight after a touchdown: `down` -1, no down text or possession, `yardLine` 85 (the conversion spot), `lastPlay` `Official Timeout`, its drive with `result` `TD`. | The red-zone flag is dropped when there is no down (`down` below 0) or the last play's drive is over; the bug read `Red zone` alone during the conversion timeout |
| NASCAR race (live) | Lap 94, 39 cars in order; still no stage, caution or lap-total field | none |
| Football (finals) | A second yellow is a `Yellow Card` (type 94) at 47' and then a `Red Card` (type 93, `redCard` true) at 62' for the same player, with no "second yellow" wording anywhere. No own goal, penalty or extra time in these games. | none: the Game Centre lists the yellow and the red, and the bug counts one red |
| Others | F1 weekend final as in the twelfth pass; UFC card final; golf `Complete`; cricket still the 2015 match; AFL and NRL summaries are the old finals. | none |

TheSportsDB at 20:42Z: livescore returned Soccer (76), Ice Hockey (24), Basketball (9) and
American Football (12); every other sport answered `{"Message":"No data found"}`. The NFL rows
read `1:55 - OT` (ESPN was five seconds of game clock further on), `End of 1st`, `5:06 - 1st`,
`7:18 - 1st` (ESPN 6:54) and eight `Final`, all with `strStatus` null. The overtime game was 222
minutes after its listed start, past the 210-minute NFL game length, so the eleventh-pass rule
for rows without a status dropped it and a TheSportsDB fixture would have gone back to "not
started"; the livescore was also no longer requested once a fixture passed its game length. A
Portuguese Primeira Liga row sat at `HT` `45` 72 minutes after the start; finished rows had
stoppage up to `90+10`. Hockey: six AHL, OHL and WHL rows at `BT`, an OHL shoot-out final `AP`;
the two DEL `P3` rows from 14:30Z were four hours stale and the AHL, USHL and WHL rows from 10
October were still there (all skipped as before). `lookuptv` returned channels for 17 of 65
events, including the three new NFL games (13, 17 and 35 rows; Canada has 16 of them, plus
Slovenia, Serbia, Croatia, Greece, Poland and New Zealand); every country named resolves.
League lists: ESPN 17 sports and 357 leagues (219 football, 48 MMA, 25 rugby union ids),
TheSportsDB 37 sports and 1,547 leagues (38 placeholders), both unchanged.

Parser changes: a TheSportsDB row without a status stays live after the game length while its
progress is a quarter clock (`1:55 - OT`) or a break (`End of 4th`, `Halftime`); a row that stops
refreshing is still dropped 30 minutes after the game length; the livescore is requested for up
to an hour after the game length for a TheSportsDB fixture that has not finished.

Checked again on this data: NFL possession, drive and timeouts, formation tags in the last play,
scoring-play times in the summary, NASCAR lap and running order, the finished F1 race, stale and
stuck TheSportsDB rows, worldwide and Canadian channels.

Confirmed now: NFL overtime, end of quarter and the red zone on the scoreboard; the NFL summary
in overtime; football second yellows; TheSportsDB NFL overtime and end-of-quarter progress.

Still unconfirmed: NFL half-time, the two-minute warning and an overtime final (`Final/OT`) on
the scoreboard; NBA overtime; an NHL empty net or shootout in progress; an MLB pitching change
or full count; tennis point score and the server in a tie-break; NASCAR stages; F1 gaps, pit
stops and fastest lap; a UFC bout in progress; live cricket; AFL/NRL live clock (off season);
golf after a cut or a playoff; football own goals, penalties and extra time.

## Fourteenth pass (12 October, 08:01)

ESPN and TheSportsDB responses captured at 08:01 Brisbane time on Monday 12 October (22:01Z on
11 October), 79 minutes after the thirteenth pass; TheSportsDB `updated` reads 23:01 London time.
In play: three NFL 4pm games (Broncos at Chargers in the 3rd quarter, 10–10 with 5:32 left; Lions at
Cardinals and 49ers at Seahawks at half-time), the first NHL game of the evening (Kraken at Capitals,
2–2 at the end of the 1st) and the NASCAR Cup race at Charlotte (lap 235). Giants at Commanders
finished as a 27–27 tie after overtime; the other 1pm games and the London game were final. NBA,
MLB, the later NHL games, A-League, URC and Champions League were still to start; every other
league showed the same finals as before. Only `nfl-summary-LIVE.json` (the Chargers game) and
`nhl-summary-LIVE.json` are new summaries; the other summaries differ from the previous upload only
in `news`, `odds` and ticket fields, and 19 of the 41 files are byte-for-byte the previous upload
(tennis, golf, F1, UFC, cricket, AFL, NRL, NBL and A-League scoreboards among them). The multi-sport
header is again a cached copy from about 19:46Z. The nested `espn-pass.zip` is the seventh-pass
capture again. The TheSportsDB calls folder holds the 22:01Z livescore and 9 new `lookuptv` files;
the coverage folder's `v2-livescore-app-*` files are the thirteenth-pass set from 20:42Z. Every
scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261012c.json` (`SportsRealDataHalfTimeTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NFL half-time | `STATUS_HALFTIME`, `state` `in`, `period` 2, `displayClock` `0:00`, `shortDetail` `Halftime`. `situation` keeps `down` 1 and a `distance` but no down text, spot or `possession`; `isRedZone` false; `lastPlay` type `End of Half`, text `END QUARTER 2`, its drive with `result` `INT` or `END OF HALF`; win probability present. `homeTimeouts`/`awayTimeouts` are the first-half counts: 0 for the Seahawks, who had used all three. In the Chargers game the Broncos used all three timeouts late in the 2nd, and both sides were back to 3 in the 3rd (`timeoutsUsed` 0 in the summary header); the college half-time in the ninth pass showed 0 and 0 the same way. | Timeouts are left out at half-time (the score bug drops the marks until the second half); the bug reads `Halftime` with no down line |
| NFL 3rd quarter | `5:32 - 3rd` straight after a touchdown and a touchback: `1st & 10 at DEN 35`, no `possession` key, `lastPlay` the kick-off with a `0 plays` drive | none: possession comes from the end of the last play (Broncos), the empty drive is left out, `Q3 · 5:32` |
| NFL overtime final | `STATUS_FINAL`, `period` 5, `shortDetail` `Final/OT`, `altDetail` `OT`; `winner` false for both sides of a 27–27 tie; fifth linescore `0` on both sides; no `situation`; records with ties (`1-3-1`, `3-1-1`, home `1-1-1`, road `0-1-1`) | none: `Final/OT`, line score `6 6 7 8 0` with an `OT` column, records read as won–lost–tied |
| NFL summary (3rd quarter) | 13 `drives.previous` plus a `drives.current` holding only the new kick-off. Kick-off 30 s after the listed start; Q1 ended at +36 min, Q2 at +85 (the two-minute warning at +71, then all three Broncos timeouts inside the last 1:37), half-time 14.6 minutes, Q3 kick-off at +100. The touchdown's drive play carries the conversion and a declined penalty as its text; `scoringPlays` has the touchdown (`Hayden Rucci 7 Yd pass from Justin Herbert (Cameron Dicker Kick)`). 109 win-probability rows. | none: scoring plays take the drive play's wallclock, last plays use the scoring text |
| NHL end of period (live) | Scoreboard `STATUS_END_PERIOD`, `shortDetail` `End of 1st`, `situation` with only `lastPlay` (`Period End`, no team). Summary: 102 `plays` with `wallclock` and `strength` relative to the team making the play (`Power Play`, `Shorthanded`, `Even Strength`); three minor penalties (`penaltyMinutes` `2`, `penaltyType` `Minor`); four goals; goalie lines with saves, shots against and save percentage; a new `onIce` array (roster references only). The 1st period began 10 minutes after the listed start and lasted 37.6 real minutes. No empty net. | none: `End of 1st`, goals at their real times, no power play shown once the period is over |
| NASCAR race (live) | Lap 235, 39 cars in order; still no stage, caution or lap-total field | none |

TheSportsDB at 22:01Z: livescore returned Soccer (60), Ice Hockey (22), American Football (12) and
Basketball (4, all `NS`); every other sport answered `{"Message":"No data found"}`. The NFL rows
read `5:32 - 3rd` (matching ESPN), `Halftime` twice, eight `Final` and, for the tie, `Final/OT`, all
with `strStatus` null. `Final/OT` was not taken as finished: within the game length such a row would
have stayed live with `Final/OT` as its clock, and after it (this one was 301 minutes after the
start) the fixture fell back to its eventsday state, which for a row without a status is "not started" until
four hours after the start. A Copa Colombia row was `P` (penalties) at 2–2 with an empty progress,
181 minutes after the start and last refreshed 46 minutes earlier, so the stuck-row rule dropped it.
The NHL game read `BT` at the end of the 1st; AHL rows `P2` `2`, an AHL shoot-out final `AP`; the
two DEL `P3` rows from 14:30Z were five hours stale, and the USHL and AHL rows from 22:00Z on 10
October were still `P3` `19`/`20` and refreshed every minute (all skipped as before). `lookuptv`
returned channels for 22 of 74 events, including the Jaguars–Eagles London game (31 rows: Sky
Sports NFL, Sky Sports Football and Sky Sports+ in the United Kingdom, DAZN in 14 countries, also
Iceland, Qatar, Czechia and Turkey), the Chargers game (16, with Bulgaria), the NHL game (7, four in
Finland) and two NBA preseason games (5 and 1 rows, both with `World`); every country named resolves. ESPN and TheSportsDB
league lists are unchanged.

Parser changes: TheSportsDB `Final/OT`, `Final/2OT` and `Final/SO` (in the livescore progress or the
eventsday status) count as finished, and the finished row keeps `Final/OT` as its status text, as on
ESPN; ESPN NFL and college football timeouts are left out at half-time.

Checked again on this data: possession after a kick-off, the red-zone flag at half-time, line scores
and the `OT` column for a tie, won–lost–tied records, scoring-play times and texts in the summary,
NHL goal times, strength and goalies, TheSportsDB quarter clocks and breaks, stuck and stale rows,
worldwide channels.

Confirmed now: NFL half-time and an overtime final (a tie) on the scoreboard; the NFL summary after
half-time; NHL end of period live on the scoreboard and summary; TheSportsDB NFL half-time and
overtime final progress; a TheSportsDB penalty shoot-out status (`P`, stuck).

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard; an MLB pitching change or full count; tennis
point score and the server in a tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap; a UFC
bout in progress; live cricket; AFL/NRL live clock (off season); golf after a cut or a playoff;
football own goals, penalties and extra time on ESPN; a refreshed TheSportsDB shoot-out row (`P`
with a progress).

## Fifteenth pass (12 October, 09:40)

ESPN and TheSportsDB responses captured at 09:40 Brisbane time on Monday 12 October (23:40Z on
11 October), 99 minutes after the fourteenth pass; TheSportsDB `updated` reads 00:40 London time on
12 October. In play: the last NFL 4pm game (49ers at Seahawks, 32–29 with 0:47 left in the 4th), three
NHL games (Kraken at Capitals in the 3rd, 5–3 with 1:35 left; Canucks at Rangers at the end of the
2nd; Hurricanes at Flyers in the 1st) and two NBA preseason games in the 2nd quarter (Magic at
Cavaliers 41–51, Bucks at Hornets 31–37). Broncos at Chargers (23–17) and Lions at Cardinals (45–21)
are now final, and the NASCAR Cup race at Charlotte finished. MLB, the NFL Sunday night game, the
later NBA game, A-League, URC and Champions League were still to start; every other league showed
the same finals as before. New summaries: `nfl-summary-LIVE.json` (the Seahawks game),
`nhl-summary-LIVE.json` (the Capitals game) and `nba-summary-LIVE.json` (the Cavaliers game); the
other summaries differ from the previous upload only in `news`, `odds` and ticket fields, and 18 of
the 41 files are byte-for-byte the previous upload (tennis, golf, UFC, cricket, AFL, NRL, NBL and
A-League scoreboards among them). The multi-sport header is again a cached copy, from about 20:42Z.
The nested `espn-pass.zip` is the seventh-pass capture again. The TheSportsDB calls folder holds the
23:40Z livescore and five new `lookuptv` files; the coverage folder's generic `v2-livescore-*` files
are from 23:39Z but its `v2-livescore-app-*` files are the fourteenth-pass set from 22:01Z, and the
league lists, `eventsday` and search files in both folders are unchanged (where the bytes differ, only
in trailing blank lines). Every scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261012d.json` (`SportsRealDataEmptyNetTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NHL empty net (live) | Summary of the Capitals game at 18:22 of the 3rd (scoreboard `1:35 - 3rd`), Capitals two goals down: `onIce` (one entry per team: `teamId` and six `entries` with `athleteid` and `whereabouts` `ROSTER_WHEREABOUTS_IN_PLAY`) lists four forwards and two defencemen for Washington and no goalie, while Seattle's six include their goalie. The goalie lines in `boxscore.players` (`goalies` group) still show Logan Thompson with 57:42 on ice; no play, strength or scoreboard field says empty net (`strength` stays `Even Strength`, the last play is `Goalie Stopped` with no team). At the end of the 1st in the fourteenth pass both goalies were on ice. | A goalie is marked as pulled while the game is in play (not at the end of a period, an intermission or a shootout) when his side has at least five players on ice, none of them a goalie from that side's goalie lines, and the other side's goalie is on ice; the goalie in net (hero sentence, Game Centre summary) is then only the other side's, the Stats tab still lists both |
| NHL timing | 1st period began 9 m 47 s after the listed start and lasted 37.6 real minutes, intermissions 18.8 and 18.9 minutes, the 2nd 38.3 minutes; 18:22 of the 3rd came 35.8 minutes into it. Goals carry `wallclock`. | none (every goal has a real time; the 35-minute period and 18-minute break estimate fits) |
| NHL scoreboard (live) | `1:35 - 3rd`, `End of 2nd` and `5:57 - 1st`; `situation` holds only `lastPlay` (`Goalie Stopped`, `Period End`, `TV Timeout`), no team, strength or goalie field | none (`P3 · 1:35`, `End of 2nd`, `P1 · 5:57`) |
| NFL last minute | `0:47 - 4th` after a turnover on downs: `1st & 10 at SF 41`, `possession` the 49ers, `homeTimeouts` 1 and `awayTimeouts` 2; `lastPlay` is the Seahawks' failed 4th-down pass but its `team` is the 49ers (the side with the ball next), its drive `5 plays, 44 yards, 1:09` with `result` `DOWNS`; win probability 0.001 for the home side; `isRedZone` false. No two-minute warning on the scoreboard; the summary has a `Two-minute warning` play at 2:00 of the 4th (wallclock 23:29:02Z, the next snap 3 m 19 s later) and one in the 2nd. | none: `Q4 · 0:47`, the down and spot as the extra line, the finished drive is left out |
| NFL summary (4th quarter) | 27 `drives.previous` plus `drives.current`, which is the finished Seahawks drive again (`result` `DOWNS`); 12 `scoringPlays`. Kick-off 19 s after the listed start; Q1 ended at +39 min, half-time at +95, Q3 ended at +153, the 4th-quarter two-minute warning at +184, 0:47 at +195. | none |
| NBA live (2nd quarter) | Scoreboard as in the eighth pass (`3:15 - 2nd`, `situation` with only `lastPlay` and win probability). Summary header competitors carry `fouls` (`teamFouls`, `teamFoulsCurrent`, `foulsToGive`, `bonusState` `DOUBLE`), `timeoutsUsed` 0 and 1 and `timeoutsRemaining` 5 for both, although the plays have two full timeouts for each side; 234 `plays` with `wallclock`, tip-off 13 minutes after the listed start. | none (team fouls and timeouts are not stored; the header timeout counts would be wrong here) |
| NASCAR race (final) | `STATUS_FINAL`, `period` 267 (laps), 39 cars in `order`, `winner` false on every car (F1 sets it on the winner); no competition `type`, stage or caution field | none: `Race · finished` with the top three from `order` |

TheSportsDB at 23:40Z: livescore returned Soccer (37), Ice Hockey (15), American Football (12) and
Basketball (4); every other sport answered `{"Message":"No data found"}`. The NFL rows read
`0:47 - 4th` (matching ESPN), ten `Final` and `Final/OT` for the tie, all with `strStatus` null. The
three NHL games were `P3` `19` (ESPN 18:25 elapsed), `BT` at the end of the 2nd and `P1` `15` (ESPN
14:03 elapsed); the two NBA games `Q2` `3` and `Q2` `9`, the minute being played (one free throw ahead
of ESPN in the Cavaliers game). A WHL shoot-out final was `AP`; an AHL `OT` `4` row from 05:04 on 10
October, the two DEL `P3` rows from 14:30Z and the USHL and AHL `P3` rows from 10 October were still
there (all skipped as before). The Copa Colombia `P` row has gone; no other shoot-out row. `lookuptv`
returned channels for the Packers–Bears game (20 rows: ESPN Australia, NFL Sunday Ticket, Sky Sports
Football, Main Event and NFL in the United Kingdom, CTV2 in Canada, DAZN in nine countries) and the
Rangers game (4, Canada and Finland); two hockey games and a Honduran football match had none.
Every country named resolves.

Parser changes: an NHL goalie is marked as pulled from the summary's `onIce` list (see the table);
`SportsGoalies.inNet` leaves him out.

Checked again on this data: NFL possession after a turnover on downs, timeouts late in the 4th, the
red-zone flag, finished drives, scoring-play times; NHL goals, strength and goalies; NBA last play and
win probability; the NASCAR running order once final; TheSportsDB quarter clocks, period minutes,
breaks, stale and stuck rows, worldwide and United Kingdom channels.

Confirmed now: an NHL empty net (summary `onIce` only); the last NFL minute after a turnover on downs;
the NFL two-minute warning in the summary plays; NBA team fouls and bonus in the summary; a finished
NASCAR race; TheSportsDB NFL last-minute clock and NBA and NHL period minutes.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard, and a shootout in the summary; an MLB pitching
change or full count; tennis point score and the server in a tie-break; NASCAR stages; F1 gaps, pit
stops and fastest lap; a UFC bout in progress; live cricket; AFL/NRL live clock (off season); golf
after a cut or a playoff; football own goals, penalties and extra time on ESPN; a refreshed
TheSportsDB shoot-out row (`P` with a progress).

## Sixteenth pass (12 October, 11:26)

ESPN and TheSportsDB responses captured at 11:26 Brisbane time on Monday 12 October (01:26Z), 106
minutes after the fifteenth pass; TheSportsDB `updated` reads 02:26 London time. In play: the NFL
Sunday night game (Ravens at Falcons, 7–6 to Atlanta with 4:34 left in the 2nd), Game 1 of the NLCS
(Dodgers at Brewers, 1–0 to Los Angeles in the top of the 4th), the last NHL game of the evening
(Hurricanes at Flyers in the 3rd, 3–1 to Carolina with 6:59 left) and an NBA preseason game in the 1st
quarter (Bulls at Nuggets, 13–12). Now final: 49ers 32–29 Seahawks, Kraken 5–3 Capitals (the empty
net of the fifteenth pass brought no goal), Rangers 6–2 Canucks, Cavaliers 119–105 Magic and Hornets
105–90 Bucks. The NFL Monday night game, A-League, URC and Champions League were still to start; every
other league showed the same finals as before. New summaries: `nfl-summary-LIVE.json` (the Falcons
game), `mlb-summary-LIVE.json` (the NLCS game), `nhl-summary-LIVE.json` (the Flyers game) and
`nba-summary-LIVE.json` (the Nuggets game); the other summaries differ from the previous upload only
in `news`, `odds` and ticket fields, and 19 of the 41 files are byte-for-byte the previous upload
(tennis, golf, F1, UFC, cricket, AFL, NRL, NBL and Premier League scoreboards among them). The
multi-sport header is again a cached copy, from about 22:01Z. The nested `espn-pass.zip` is the
seventh-pass capture again. The TheSportsDB calls folder holds the 01:26Z livescore and four new
`lookuptv` files; the coverage folder's generic `v2-livescore-*` files are from 01:26Z but its
`v2-livescore-app-*` files are the fifteenth-pass set from 23:40Z, and the league lists, `eventsday`
and search files in both folders are unchanged. Every scoreboard and summary parses without errors.
Trimmed copies are in `app/src/test/resources/sports/*-real-20261012e.json` (`SportsRealDataPlayoffTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| MLB full count (live) | Scoreboard `Top 4th` (`periodPrefix` `Top` in the summary), `situation` with `balls` 3, `strikes` 2, `outs` 2, `onSecond` true, and `pitcher` and `batter` with names and a game line (`3.2 IP, ER, 5 H, 4 K, 2 BB`; `0-0, BB`). Line scores `0 0 0` for the home side and `0 1 0 0` for the side batting. | none for the count (`▲4`, `2 outs · 3–2`, runner on second) |
| MLB scoreboard last play | The first pitch seen as the scoreboard's `lastPlay`: `summaryType` `P`, text `Pitch 5 : Ball 3`, type `Ball` with `alternativeText` `Walk`, its `team` the fielding side; no win probability. | The scoreboard's last play is left out when it is a pitch (`summaryType` `P`), as the summary already does; the Sport hero read `Pitch 5 : Ball 3.` under the count |
| MLB summary (top 4th) | One pitch behind the scoreboard (`situation` 2–2, its `onSecond` an object with `playerId`, pitcher and batter as ids only, named in the box score). A balk is two plays with the same text (`T. Hernández to second on a balk.`): a `balk` play without `summaryType` and a `play-result` (`N`), both with `resultCount` 0–0 although the count stayed 1–1. Two challenged pitch calls appear as pitch types `Ball - Overturned` and `Strike Looking - Overturned` (`summaryType` `P`). Both starters were still pitching (no pitching change). Top of the 1st 16.8 minutes after the listed start; the first three innings took 16.2, 26.7 and 18.4 minutes. The one run has `wallclock`. | Last plays leave out a play whose text repeats the next one; the list showed the balk twice |
| NFL defensive touchdown | Summary `scoringPlays`: `Fumble Return Touchdown` (type 39, `scoringType` `touchdown`), `team` the Falcons, who were on defence; in `drives` it closes a Ravens drive with `result` `FUMBLE RETURN TD`, and the drive play has no `team`, `start` the Ravens and `end` the Falcons. Kick-off 3.5 minutes after the listed start, the 1st quarter ended at +42 minutes. | none: the touchdown goes to the side in `scoringPlays`, with the drive play's `wallclock` |
| NFL 2nd quarter (live) | `4:34 - 2nd`, `3rd & 6 at BAL 42`, `possession` the Falcons, `lastPlay` a rush with its drive `5 plays, 36 yards, 2:47` and no result, timeouts 3 and 3, `isRedZone` false, win probability 0.6463. | none (`Q2 · 4:34`, the drive shown, `65%`) |
| NHL power play ending (live) | Flyers game summary at 13:01 of the 3rd: a cross-checking minor at 10:18 (`penaltyMinutes` 2, `Minor`), `Power Play` for Carolina on their plays and `Shorthanded` on the Flyers' until 12:10, then `Even Strength` from 12:24; scoreboard `situation` again holds only `lastPlay` (a face-off). Both goalies are in `onIce`. | none: power play from the penalty, gone once the strength is even again; no empty net |
| NHL timing | 1st period began 9.5 minutes after the listed start and lasted 40.0 real minutes (four minors), intermissions both 18.7 minutes, the 2nd 36.5 minutes; 13:01 of the 3rd came 22.4 minutes into it. Every goal has `wallclock`. | none |
| NBA live (1st quarter) | `7:04 - 1st`, `lastPlay` `Bulls Full timeout` with win probability. Summary header `timeoutsUsed` 0 and 1 and `timeoutsRemaining` 7 and 6 (this time matching the plays), `fouls` with `teamFouls` 2 and 5, `foulsToGive` 2 and 0, `bonusState` `DOUBLE` on the side shooting in the bonus. | none (team fouls and timeouts are not stored) |

TheSportsDB at 01:26Z: livescore returned Soccer (22), Ice Hockey (36), Basketball (27), American
Football (13) and Baseball (5, the first baseball rows since 10 October); every other sport answered
`{"Message":"No data found"}`. The baseball rows were all `NS` with an empty progress: three KBO
games, a Venezuelan league game and an MLB Brewers–Dodgers row (`2616724`) timed 21:00Z on 12 October,
while ESPN had Game 1 of that series in the 4th inning; no row was live. The NFL rows read `4:34 -
2nd` (matching ESPN), eleven `Final` and `Final/OT` for the tie, all with `strStatus` null. NBA: `Q1`
`5` for the Nuggets game (ESPN 7:04 left) with the scores the wrong way round (Denver 12, Chicago 13;
ESPN had Denver 13–12 and never 12–13), and the two finals `FT` `12`. NHL: `P3` `13` for the Flyers
game (ESPN 13:01 elapsed), the Rangers game `FT`. An AHL row read `AOT` `20` at 2–2, last refreshed 52
minutes earlier (a tie after overtime, which the AHL settles by a shoot-out; it parses as a 2–2
final); a WHL row was `P3` `20` at 1–4 146 minutes after its start and refreshed each minute, and an
FPHL row `P2` `8`. Rows not yet finished carry the current `updated` time even when nothing changed
(`NS` rows too), so `updated` shows whether TheSportsDB still refreshes a row, not whether it moved.
The two DEL `P3` rows from 14:30Z were last refreshed 8.6 hours earlier (skipped as before).
`lookuptv` returned channels for the Ravens–Falcons game (38 rows: Sky Sports NFL and Main Event in
the United Kingdom, ESPN Australia and Kayo Sports, ESPN New Zealand, NFL Sunday Ticket, NBC (listed
for one Philadelphia station only), Universo and three local stations in the United States, CTV, TSN
and RDS in Canada, DAZN in Spain, Brazil and Turkey, also France, the Netherlands, South Africa,
Slovenia, Bosnia and Herzegovina, Germany, Qatar, Serbia and Greece) and none for the three KBO games.
Every country named resolves.

Parser changes: the ESPN scoreboard's last play is left out when it is a pitch; repeated play texts
are dropped from the baseball last plays (see the table).

Checked again on this data: NFL possession, drive, timeouts and line scores; MLB count, outs, runners,
pitcher and batter from the summary `situation` and box score; NHL goals, strength after a penalty,
goalies and `onIce`; NBA last play and win probability; TheSportsDB quarter clocks, period minutes,
`Final/OT`, stale rows, Canadian and United States channels.

Confirmed now: an MLB full count on the scoreboard and a balk in the summary; challenged pitch calls
in the MLB plays; an NFL defensive touchdown in the summary; an NHL power play ending on time in the
summary.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard, and a shootout in the summary; an MLB pitching
change; a live TheSportsDB baseball row; tennis point score and the server in a tie-break; NASCAR
stages; F1 gaps, pit stops and fastest lap; a UFC bout in progress; live cricket; AFL/NRL live clock
(off season); golf after a cut or a playoff; football own goals, penalties and extra time on ESPN; a
refreshed TheSportsDB shoot-out row (`P` with a progress).

## Seventeenth pass (12 October, 11:43)

ESPN and TheSportsDB responses captured at 11:43 Brisbane time on Monday 12 October (01:43Z), 17
minutes after the sixteenth pass; TheSportsDB `updated` reads 02:43 London time. In play: the NFL
Sunday night game (Ravens at Falcons, 14–6 to Atlanta with 0:12 left in the 2nd), Game 1 of the NLCS
(Dodgers at Brewers, 1–1 in the top of the 5th) and the NBA preseason game (Bulls at Nuggets, 26–25
with 55.4 left in the 1st). Hurricanes at Flyers finished 4–1 to Carolina, so no NHL game was live.
Every other league showed the same finals or the same schedule as before. New summaries:
`nfl-summary-LIVE.json`, `mlb-summary-LIVE.json` and `nba-summary-LIVE.json` (the same three games);
`nhl-summary.json` is now the final of the Capitals game (the scoreboard's first event). The other
summaries differ from the previous upload only in `news`, `odds` and ticket fields, and 23 of the 41
files are byte-for-byte the previous upload (tennis, golf, F1, NASCAR, UFC, cricket, AFL, NRL, NBL,
A-League, URC, Premier League and Champions League scoreboards among them). The multi-sport header is
again a cached copy, from about 23:40Z. The nested `espn-pass.zip` is the seventh-pass capture again.
The TheSportsDB calls folder holds the 01:43Z livescore and one new `lookuptv` file; the coverage
folder's generic `v2-livescore-*` files are from 01:43Z but its `v2-livescore-app-*` files are the
sixteenth-pass set from 01:26Z, and the league lists, `eventsday` and search files in both folders
are unchanged apart from trailing blank lines. Every scoreboard and summary parses without errors.
Trimmed copies are in `app/src/test/resources/sports/*-real-20261012f.json`
(`SportsRealDataFreeThrowTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NBA scoreboard last play | `55.4 - 1st`, `situation` with only `lastPlay`: type `Free Throw - 2 of 2`, text `misses free throw 2 of 2`, no `team` and no athlete, win probability 0.531. This is the third free-throw last play seen on the scoreboard (also 11 October, twice) and each had lost the player's name; the summary had not yet reached the free throws. | Basketball last plays (scoreboard and summary) whose text has no subject start with a capital (`Misses free throw 2 of 2`); the Sport hero read `misses free throw 2 of 2.` |
| NBA summary plays without a player | 127 `plays`. The tip-off is `vs. ` (type `Jumpball`, `shortDescription` `Jump Ball`, no team or participants) with a `wallclock` 20 minutes before the first play; `delay of game violation` (`shortDescription` `Violation`) has no team either. Earlier captures held `shot clock turnover `, `foul` and `makes 6-foot shot` with a team but no player. Every play carries `shortDescription` (`Turnover`, `+3 Points`, `Missed 3PT`, `Rebound`, `Timeout`). First play 13.6 minutes after the listed start; 11:50 to 0:55 of the 1st quarter took 29.3 minutes (two full timeouts). Header `timeoutsUsed` 1 and 1, `timeoutsRemaining` 6 and 6, `teamFouls` 5 and 9, both `bonusState` `DOUBLE`. | The same capital; a bare `vs.` reads as the play's `shortDescription` (`Jump Ball`), or its type on the scoreboard |
| NFL timeout late in the half | `0:12 - 2nd`, `2nd & 10 at ATL 26`, `possession` the Ravens, `lastPlay` type `Timeout`, text `Timeout #1 by ATL at 00:12.`, its `team` the Ravens (the side with the ball, not the side that called it) and its drive `8 plays, 54 yards, 2:12` with no result; `homeTimeouts` 2 and `awayTimeouts` 2 (summary header `timeoutsUsed` 1 and 1); win probability 0.7366. The two-minute warning is again only in the summary (`Two-minute warning` at 2:00 of the 2nd, wallclock 01:33:26Z, the next snap 2 m 42 s later); the scoreboard showed the timeout instead. | none: `Q2 · 0:12`, the Ravens drive shown, timeouts 2 and 2 |
| NFL summary (end of 2nd quarter) | 10 `drives.previous` plus `drives.current` (the Ravens drive from the kick-off after the Falcons touchdown at 2:24, 13 plays including both timeouts and the warning); four `scoringPlays`. | none |
| MLB scoreboard last play (new at-bat) | `Top 5th`, `outs` 1, count 0–0, `onFirst` true; `lastPlay` type `Start Batter/Pitcher` (`summaryType` `A`), text `Jacob Misiorowski pitches to Freddie Freeman`, `team` the batting side; pitcher line `4.1 IP, ER, 5 H, 5 K, 3 BB`. Line scores `0 0 0 1` and `0 1 0 0 0`. | none: kept as the last play (it names pitcher and batter); `▲5`, `1 out · 0–0` |
| MLB summary (top 5th) | 268 plays. Both starters still pitching, no pitching change. A home run (`Turang homered to left center (384 feet).`, `scoringPlay`, `wallclock`) tied the game in the bottom of the 4th. A new pitch type `ball---confirmed` (a challenged ball upheld; `summaryType` `P`). The 4th inning took 21.9 minutes (top 8.9, break 3.1, bottom 6.9, break 3.0). `situation` matches the scoreboard this time. | none: the run is a moment with its real time, pitches stay out of the last plays |
| NHL final summary | The Capitals game after the final: `onIce` still lists six Washington skaters and no goalie (the empty net at the horn), Seattle's six include their goalie; `featuredAthletes` names winning and losing goalies and three stars. The last goal (17:20 of the 3rd) came before the goalie was pulled, so the empty net brought no goal. | none: no goalie is marked as pulled once the game is final |

TheSportsDB at 01:43Z: livescore returned Ice Hockey (34), Basketball (27), Soccer (20), American
Football (13) and Baseball (5); every other sport answered `{"Message":"No data found"}`. The NFL
night game read `0:12 - 2nd` at 14–6 (matching ESPN). The Nuggets game read `Q1` `11` at 25–25,
one free throw behind the ESPN scoreboard (the summary was 25–25 too). The Flyers game was `FT` at
1–4 (refreshed 01:42:30Z), and the WHL row that was `P3` `20` in the sixteenth pass finished `FT`
1–4, 150 minutes after its start; an FPHL row was `P2` `16`. The AHL `AOT` row and the USHL and AHL
rows from 10 October have gone; the two DEL `P3` rows from 14:30Z were last refreshed 8.9 hours
earlier (skipped as before). The five baseball rows were still `NS` with an empty progress, the MLB
row timed 21:00Z on 12 October while ESPN had Game 1 in the 5th; no row was live. The three 4pm NFL finals
and the 1pm tie still carry the current `updated` time; the other 1pm finals stopped at 22:59Z and the
London game at 19:29Z.
`lookuptv` for the WHL game returned `{"tvevent":null}`; the coverage folder's three KBO files were
null too and its Ravens–Falcons file is the sixteenth-pass 38 rows.

Parser changes: basketball last plays without a subject start with a capital, and a bare `vs.`
tip-off reads as `Jump Ball` (see the table).

Checked again on this data: NFL possession, drive, timeouts, line scores and win probability after a
timeout; scoring-play times; MLB count, outs, runners, pitcher and batter, the home run moment;
NBA win probability and line score; NHL final goalies and awards; TheSportsDB quarter clocks, period
minutes, hockey finals, stale rows.

Confirmed now: an NFL timeout as the scoreboard's last play (credited to the side with the ball);
the NFL two-minute warning in the summary for the second time (still never on the scoreboard); an
MLB new at-bat as the scoreboard's last play; a challenged pitch call upheld; the empty-net `onIce`
state kept after the final.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard, and a shootout in the summary; an MLB pitching
change; a live TheSportsDB baseball row; tennis point score and the server in a tie-break; NASCAR
stages; F1 gaps, pit stops and fastest lap; a UFC bout in progress; live cricket; AFL/NRL live clock
(off season); golf after a cut or a playoff; football own goals, penalties and extra time on ESPN; a
refreshed TheSportsDB shoot-out row (`P` with a progress); an NBA free throw on the scoreboard with
the player's name.

## Eighteenth pass (12 October, 12:06)

ESPN and TheSportsDB responses captured at 12:06 Brisbane time on Monday 12 October (02:06Z), 23
minutes after the seventeenth pass; TheSportsDB `updated` reads 03:06 London time. In play: the NFL
Sunday night game (Ravens at Falcons, 14–9 to Atlanta with 12:39 left in the 3rd), Game 1 of the NLCS
(Dodgers at Brewers, 2–1 to Milwaukee in the top of the 6th) and the NBA preseason game (Bulls at
Nuggets, 52–39 to Chicago with 6:03 left in the 2nd). No NHL game was live and every other league
showed the same finals or the same schedule as before; four WTA matches due at 03:00Z are marked
`recent` while still scheduled (not read). New summaries: `nfl-summary-LIVE.json`,
`mlb-summary-LIVE.json` and `nba-summary-LIVE.json` (the same three games); `nhl-summary-LIVE.json` is
still the sixteenth-pass file. The other summaries differ from the previous upload only in `news`,
`odds` and ticket fields, and 21 of the 41 files are byte-for-byte the previous upload. The
multi-sport header is again a cached copy, from about 01:26Z. The nested `espn-pass.zip` is the
seventh-pass capture again. The TheSportsDB calls folder holds the 02:06Z livescore and three new
`lookuptv` files; the coverage folder's generic `v2-livescore-*` files are from 02:06Z but its
`v2-livescore-app-*` files are the seventeenth-pass set from 01:43Z, and the league lists,
`eventsday`, search and older `lookuptv` files in both folders are unchanged apart from blank lines.
Every scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261012g.json` (`SportsRealDataPitchingChangeTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| MLB pitching change (summary) | Two relief plays, the first seen: `Hall relieved Misiorowski` in the top of the 5th with one out and a runner on first, and `Uribe relieved Hall` before the top of the 6th. The at-bat start the seventeenth-pass scoreboard showed (`Jacob Misiorowski pitches to Freddie Freeman`) keeps its play id but now reads `DL Hall pitches to Freddie Freeman` with a later `wallclock`; the relief play's id sorts just before it. Each is type `play-result` with `summaryType` `C`, `team` the fielding side, one `pitcher` participant (the new pitcher), `resultCount` 0–0 and `outs` 0 whatever the real outs. The second has a `wallclock` (02:04:12Z) earlier than the `Top of the 6th inning` play listed before it (02:05:48Z). `situation` names the new pitcher by id (`playerId`), found in the box score (`Relief Pitcher`). | none: the count names the new pitcher (`Abner Uribe` to `Will Smith`), and the relief play is one of the last plays (`Abner Uribe pitches to Will Smith`, `Uribe relieved Hall`, `Top of the 6th inning`) |
| MLB scoreboard after the change | `Top 6th`, `outs` 0, count 0–1, bases empty; `pitcher` the reliever (`position` `RP`, game line `0.0 IP, 0 ER, 0 H, 0 BB`); `lastPlay` the first pitch (`summaryType` `P`, `Pitch 1 : Strike 1 Looking`). Line scores `0 0 0 1 1` and `0 1 0 0 0 0`. | none: the pitch is left out, so no last play; `▲6`, `0 outs · 0–1` |
| MLB plays (outs) | Every pitch of an at-bat carries the outs at the end of that at-bat: Freeman's pitches read `outs` 3 before he grounded into a double play with one out. `situation` had the right outs. | none: outs come from `situation` first |
| MLB timing | The 5th inning took 25.9 minutes (top 7.4 with the first pitching change, break 3.2, bottom 12.0, break 3.3, the second change made during that break). | none |
| NFL second half (live) | `12:39 - 3rd`, `1st & 10 at ATL 47`, `possession` the Falcons, `lastPlay` a pass with its drive `3 plays, 12 yards, 1:37` (the play's `team` the side with the ball); `homeTimeouts` 3 and `awayTimeouts` 3 again after the Ravens used all three in the 1st half (summary header `timeoutsUsed` 0 and 0); win probability 0.7701. Line scores `7 7 0` and `3 6 0`. | none: `Q3 · 12:39`, the drive shown, timeouts 3 and 3 |
| NFL summary (early 3rd quarter) | Clock 13:23, the time of the scoreboard's last play (the scoreboard clock had run on to 12:39). The half ended with a 51-yard field goal on the last play (`0:00`, `wallclock` 01:47:26Z) after the Ravens' third timeout at 0:03, then `End of Half` (`END QUARTER 2`). The 2nd-half kick-off came 14.9 minutes later (02:02:22Z); the 1st half took 84.0 minutes from kick-off. The 3rd-quarter drive is in both `drives.previous` and `drives.current`. | none: the field goal is a moment at 0:00 of the 2nd with its real time; the drive's plays are listed once |
| NBA 2nd quarter (live) | `6:03 - 2nd`, `lastPlay` `Isaac Okoro personal foul` with the player and win probability 0.189 for Denver. Summary 10 seconds of game clock behind (6:13: a substitution, `Nuggets Full timeout`, a goaltending violation), a 7–0 Bulls run. Header `timeoutsUsed` 2 and 0 but `timeoutsRemaining` 4 and 6 (Denver had called three, Chicago one, so `timeoutsUsed` counts the current quarter only); `fouls` with `teamFouls` 6 and 13, `teamFoulsCurrent` 1 and 4. The 1st quarter took 31.3 minutes; the first play of the 2nd came 3.9 minutes after its end. | none (team fouls and timeouts are not stored) |

TheSportsDB at 02:06Z: livescore returned Ice Hockey (34), Basketball (25), Soccer (16), American
Football (13) and Baseball (5); every other sport answered `{"Message":"No data found"}`. Football
extra time, the first seen: an MLS Next Pro game read `ET` `94` at 3–3 (`2H` `80` at 3–3 in the
seventeenth pass, `2H` `63` at 3–2 in the sixteenth), refreshed each minute. Other football rows:
`HT` `45` (two), `1H` `45+5`, `2H` `76` and `2H` `49`, and ten finals with stoppage minutes up to
`90+7`. The NFL night game read `13:23 - 3rd` at 14–9 (the ESPN summary's clock; the scoreboard had
12:39). The Nuggets game read `Q2` `6` at 39–52 (ESPN 6:03 left), a Mexican LNBP game `Q4` `7`. The
FPHL row that was `P2` `16` at 2–1 is now `BT` at 2–2 with no progress (an interval). The two DEL
`P3` rows from 14:30Z were last refreshed 9.3 hours earlier (skipped as before). The five baseball
rows were still `NS` with an empty progress, the MLB row timed 21:00Z on 12 October while ESPN had
Game 1 in the 6th; no row was live. The 4pm NFL finals and the 1pm tie (`Final/OT`) still carry the
current `updated` time. `lookuptv` for the new Japanese B1, FPHL and LNBP games returned
`{"tvevent":null}`, as did the coverage folder's WHL file.

Parser changes: none. The new shapes (a pitching change, timeouts back after half-time, football
extra time and a hockey interval on TheSportsDB) already read correctly; the fixtures and tests keep
them that way.

Checked again on this data: NFL possession, drive, timeouts after half-time, line scores and win
probability; scoring-play times; MLB count, outs, runners, pitcher and batter after a pitching change,
the last plays and run moments; NBA last play, win probability, line score and scoring run;
TheSportsDB quarter clocks, football minutes, half-time and extra time, hockey intervals, stale rows,
NFL finals.

Confirmed now: an MLB pitching change, mid-inning and between innings, in the summary and on the
scoreboard (the new pitcher and his game line); NFL timeouts back to three each for the 2nd half;
football extra time on TheSportsDB (`ET` with the minute); a TheSportsDB hockey interval after a goal
(`BT`, no progress).

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard, and a shootout in the summary; a pitching change
as the MLB scoreboard's last play; a live TheSportsDB baseball row; tennis point score and the server
in a tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap; a UFC bout in progress; live
cricket; AFL/NRL live clock (off season); golf after a cut or a playoff; football own goals, penalties
and extra time on ESPN; a TheSportsDB penalty shoot-out after extra time and a refreshed shoot-out row
(`P` with a progress); an NBA free throw on the scoreboard with the player's name.

## Nineteenth pass (12 October, 21:03)

ESPN and TheSportsDB responses captured at 21:03 Brisbane time on Monday 12 October (11:03Z), 8 hours
57 minutes after the eighteenth pass; TheSportsDB `updated` reads 12:02 London time. No team game was
in play on ESPN: the NFL Sunday night game finished in overtime (Ravens 30–23 at Falcons), Game 1 of
the NLCS finished 2–1 to Milwaukee and the Nuggets beat the Bulls 133–128 in regulation. In play were
only tennis matches: two in Shanghai (ATP, 1st set) and nine in Wuhan, Mallorca, Rovereto and Lisbon
(WTA). The NFL Monday night game, the Premier League Monday game, the Champions League, college
football, the A-League, URC and a UFC Contender Series card were still to start, and every other
league showed the same finals as before. New summaries: `mlb-summary.json` is the NLCS final, and the
NFL, college football and Premier League summaries are pre-game files for the next games; the four
`*-summary-LIVE.json` files are the eighteenth-pass files (nothing live to replace them), and 13 of
the 41 files are byte-for-byte the previous upload. The multi-sport header is again a cached copy
(nothing in play, the newest final from 06:30Z). The nested `espn-pass.zip` is the seventh-pass
capture again. The TheSportsDB calls folder holds the 11:02Z livescore and five new `lookuptv` files;
the coverage folder's generic `v2-livescore-*` files are from 11:02Z but its `v2-livescore-app-*`
files are the eighteenth-pass set from 02:06Z. Both league lists gained six football leagues (1,553,
none renamed or removed); `eventsday`, search and ESPN league files are unchanged apart from blank
lines. Every scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261012h.json` (`SportsRealDataInningTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NFL overtime win (final) | `STATUS_FINAL`, `period` 5, `shortDetail` `Final/OT`, `altDetail` `OT`; fifth linescore `7` for Baltimore and `0` for Atlanta (8 points in the 4th to tie 23–23); `winner` true on the Ravens; no `situation`; records `4-1` and `2-3`. | none: `BAL 30–23 ATL`, `Final/OT`, an `OT` column |
| MLB final (summary) | 545 plays. The home side did not bat in the 9th (8 home linescores, 9 away), as on 11 October. Seven pitching changes in all, each `summaryType` `C`; `Megill relieved Ashby` has a `wallclock` (03:00:40Z) before `Top of the 9th inning` (03:03:05Z) listed ahead of it, as in the eighteenth pass. A pinch hitter (`De Paula hit for E. Hernández`) and a pinch runner (`Edman ran for De Paula`) are `play-result` with `summaryType` `N` and `outs` 0. No end-of-game play: the plays stop at the last out's `end-batterpitcher`. The root `situation` is absent this time (the 11 October final still had the last count). `featuredAthletes` names the winning, losing and saving pitcher. First pitch 00:16:45Z, last out 03:11:56Z (2 h 55 min). | none: no count once final, the last plays end with `Betts flied out to center.`, awards read |
| NBA, NHL finals | Nuggets 133–128 in four quarters (39–52 at 6:03 of the 2nd in the eighteenth pass). The Flyers game final now has five `featuredAthletes` (both goalies and three stars). | none |
| NASCAR (final, next day) | The Charlotte race now has `winner` true on the first car (`order` 1); in the fifteenth pass, soon after the finish, every car had `winner` false. | none: the order comes from `order` |
| Tennis (live) | Eleven live matches. Server (`possession`) on 9 of them, again only where the match also has a `situation` with `onFirst`… all false. A 3rd set after a set lost in a tie-break (`value` 6 with `tiebreak` 2 against 7 with `tiebreak` 7, `winner` true on the side that took it). Still no point score and no tie-break in progress. | none: `MCNALLY 2 7 4 / STARODUBTSEVA 6 6(2) 4`; `BERGS serving for the set` at 6–5 |
| UFC (next card) | Dana White's Contender Series week 10 on 13 October 23:00Z, `STATUS_SCHEDULED`, bouts with records and weight classes. | none |

TheSportsDB at 11:02Z: livescore returned Ice Hockey (31), Basketball (16), American Football (13),
Baseball (6) and Soccer (3); every other sport answered `{"Message":"No data found"}`. The first live
baseball rows: three KBO games listed at 09:30Z read `IN4` at 3–5 and `IN5` at 1–3 and 2–1, with
`strProgress` the same code as `strStatus` (no half-inning, outs or count) and the current `updated` time; a
CPBL game listed at 10:35Z read `IN2` with both scores null. Until now such a row showed `IN4` as its
state on the score bug, the cards and the hero. The MLB row `2616724` (21:00Z on 12 October) and a
Venezuelan game were still `NS`. Football: a European Under-17 game `HT` `45+3`, a Ukrainian game
`1H` `10` 62 minutes after its listed start (a late kick-off; the live window still covers it) and a
Tajik game `1H` `3`. A KHL game read `P3` `19` at 1–0, a KBL game `HT` with a null progress. The NFL
night game reads `Final/OT` (in `strProgress`, `strStatus` null) at 23–30 and, like the Commanders–Giants
tie, keeps the current `updated` time, while the regulation finals stopped refreshing (03:04 and 03:24
London for the 4pm games, 23:59 for the 1pm games). The two DEL `P3` rows from 14:30Z on 11 October
were last refreshed 18.2 hours earlier (skipped as before). `lookuptv` for the new KHL, KBL, Bulgarian
and Serbian basketball and CPBL games returned `{"tvevent":null}`; the Packers–Bears file has the same
20 channels with its `strTime` corrected from 20:25:00 to 17:00:00.

Parser changes: a TheSportsDB baseball row `IN<n>` (livescore or `eventsday`) is live in inning `n`
and reads `Inning n` (score bug, cards, hero and multiview), with the repeated code dropped as a clock.

Checked again on this data: NFL overtime final, line score and records; TheSportsDB `Final/OT`
progress; MLB final line score, awards and last plays; tennis sets, completed tie-break sets and
server; football half-time and minutes, a late kick-off; hockey period minutes; stale rows; the
schedules of the next NFL, college football, Premier League, Champions League and UFC events.

Confirmed now: live TheSportsDB baseball rows (`IN<n>`, the inning only, scores sometimes null); an
NFL overtime win on the scoreboard; NASCAR `winner` set on the winning car some hours after the race;
an MLB final without the root `situation`.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard, and a shootout in the summary; a pitching change
as the MLB scoreboard's last play; TheSportsDB baseball half-innings, extra innings or a finished
baseball row; tennis point score and the server in a tie-break; NASCAR stages; F1 gaps, pit stops and
fastest lap; a UFC bout in progress; live cricket; AFL/NRL live clock (off season); golf after a cut
or a playoff; football own goals, penalties and extra time on ESPN; a TheSportsDB penalty shoot-out
after extra time and a refreshed shoot-out row (`P` with a progress); an NBA free throw on the
scoreboard with the player's name.

## Twentieth pass (13–16 October)

Seven ESPN passes, each with a TheSportsDB run, captured between Wednesday 14 and Saturday 17
October, Brisbane time: 06:23 on the 14th (20:23Z on the 13th; TheSportsDB `updated` 21:23 London),
09:03 (23:03Z; 00:02), 11:07 (01:07Z on the 14th; 02:06), 11:45 (01:45Z; 02:44), 08:01 on the 15th
(22:01Z on the 14th; 23:01), 19:39 on the 15th (09:39Z; 10:39) and 07:38 on the 17th (21:38Z on the
16th; 22:37). The upload folders are reused, so each pass also carries summaries from earlier passes
whose league had nothing live since; the nested `espn-pass.zip` is the 11 October 05:07 capture
again in all seven. In play: seven Champions League games in the 2nd half (63' to 66') in the first
pass; NHL games in the second to fourth (four, then eleven, then nine at once), the NBA (three games,
then one) and Game 2 of the ALCS (Guardians at Rays, `Top 4th` and `End 5th`) in the third and
fourth; a Contender Series card in the second and third; an NBL game (`9:02 - 3rd`) and three WTA
matches in the sixth; nothing in the fifth and seventh. Finals of note: the NFL Monday night game
(Rams 34–37 Bills, `Final/OT`, an overtime field goal) and the Thursday game (Broncos 17–14
Seahawks, `Final/OT`, a 53-yard field goal 5:07 into overtime) with its summary; NHL `Final/OT`
(Devils 5–4 at Red Wings) and two `Final/SO` (Canadiens 4–3 Sabres, Stars 3–2 at Avalanche); 18
Champions League games; Game 3 of the NLCS (Brewers 6–2 at Dodgers); Sydney FC 0–0 Western Sydney in
the A-League. 11 to 23 of the 42 files in each pass are byte-for-byte the pass before; the
multi-sport header lags as before. Each TheSportsDB calls folder holds that run's livescore
(`v2-livescore-all` and the per-sport `app` files; the second run's `app-soccer` file is the first
run's, and the sixth run has no American football file) and 1 to 10 new `lookuptv` files; each
coverage folder's `v2-livescore-app-*` files are the previous run's. League lists (1,553) are
unchanged. Every scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261014*.json`, `*-real-20261015*.json` and
`*-real-20261017.json` (`SportsRealDataMidweekTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| Football substitution (live summary) | Arsenal–Lille at 66' (first pass): the three substitutions at 65' are `keyEvents` with `participants` [on, off] but the text `Bukayo Saka (Arsenal) Substitution at 65'`, which names only the player coming on. The `commentary` entry for the same play id already reads `Substitution, Arsenal. Bukayo Saka replaces Noni Madueke.` Across every football summary uploaded so far, all 167 substitution key events have a commentary entry with the same id, and 164 of them have the same text (the other three are these). | Substitution moments take the commentary text of the same play when there is one (`Substitution, Arsenal. Bukayo Saka replaces Noni Madueke.`) |
| Football own goals and penalties (scoreboard) | `Own Goal` (`type.id` 97, `ownGoal` true) six times: `team` is the side credited with the goal (Feyenoord) while the athlete's own `team` is the other side (Como's Robert Sánchez). The summary key event reads `Own Goal by Robert Sánchez, Como. Feyenoord 3, Como 1.` (`shortText` `Robert Sánchez Own Goal`, `team` Feyenoord, `ownGoal` null). Penalties scored are `Penalty - Scored` with `penaltyKick` true (21); no missed penalty, extra time or shoot-out. | Scoreboard own goals carry the marker the summary already gives (`R. Sánchez (own goal)`); the side was already right |
| MLB scoreboard after an at-bat | `Top 4th`, `outs` 2, `onFirst` true, `balls` 1 and `strikes` 2; `lastPlay` `Halpin singled to right.` (`play-result`, `summaryType` `N`, `alternativeType` `Single`, `athletesInvolved` Halpin) while `batter` is still Halpin (`summary` `1-2, K`). The count is the finished at-bat's; the summary, fetched just before, was still mid at-bat (`Freddy Peralta pitches to Petey Halpin`, 1–2). | The scoreboard count reads 0–0 once the last play is an at-bat result by the listed batter (as the summary already does after `end-batterpitcher`): `▲4`, `2 outs · 0–0` instead of `2 outs · 1–2`. A result led by another player (a balk, a steal) keeps the count |
| MLB scoreboard between innings | `End 5th`, count and outs 0, no pitcher or batter, `lastPlay` `End of the 5th inning` (`end-inning`, no `summaryType`). | none: `End 5`, no count |
| MLB final (summary) | 570 plays, first pitch 00:09:29Z, last out 03:11:18Z. A run on a wild pitch arrives as two scoring plays with the same text, score and `wallclock`: a `wild-pitch` play without `summaryType` and a `play-result` (`S`). Six pitching changes; no root `situation`; `featuredAthletes` winning, losing and saving pitcher. | Scoring moments that repeat the same text, period, clock and score are listed once (the run showed twice in Game Centre) |
| NHL bench minor (summary) | `Too Many Men on the Ice`, `penaltyType` `Bench`, `penaltyMinutes` `2`; the penalty play itself is `Even Strength`, the next faceoff `Power Play` for the other side. Penalty types in all NHL summaries so far: `Minor` 261, `Major` 2, `Misconduct` 1, `Bench` 1. | `Bench` counts as a power-play penalty, so a summary that ends on it already shows the power play |
| NHL (live and finals) | Up to eleven games at once. Intervals are `STATUS_END_PERIOD` (`End of 1st`, clock `0:00`); a new period shows `20:00 - 2nd` with the last play `End of 1st Period`. `situation` holds only `lastPlay`. Shoot-out finals `Final/SO` with five line-score columns (`1 2 0 0 1` and `1 0 2 0 0`). Two of the live summaries had 23 and 29 power-play plays, but every capture fell at even strength, and no goalie was pulled. | none: `End of 1st`, `P2 · 20:00`, an `SO` column |
| UFC card | Second pass: event `STATUS_IN_PROGRESS`, the first bout `STATUS_PRE_FIGHT` (state `in`, `Pre-fight`), the rest scheduled. Third: four bouts final, the last pre-fight. Fourth and fifth: all five bouts `STATUS_FINAL` but the event's own status back to `STATUS_SCHEDULED` (`Scheduled`). Finishes `Unofficial Winner Decision` (22) and `Unofficial Winner Kotko` (21, KO/TKO); records updated on the card (5-0-0 to 5-1-0). | A finished card no longer reads `Scheduled`: when the event's status disagrees with the bouts, the last bout's (`Final`) is shown |
| NBA (live) | `8:11 - 4th` and `10:58 - 4th` (blowouts, win probability 100), `37.4 - 2nd`, `4:01 - 3rd` with the last play `Thunder Full timeout`. All finals in regulation; no free throw as a last play. | none |
| NFL | No game in play. Two more overtime finals on the scoreboard (`period` 5; Bills `3` and Broncos `3` in the fifth column, no `situation`); the Broncos summary ends with `END GAME` at 4:53 of overtime after `Wil Lutz 53 Yd Field Goal`. | none: `Final/OT`, an `OT` column |
| Tennis (live) | Three WTA matches; as before the server (`possession`) comes only with a `situation` (`onFirst`… all false), here on the two doubles and not the singles. `HUNTER/SINIAKOVA serving for the set` at 6–3, 5–2 (also for the match). Still no point score and no tie-break in progress. | none |
| NBL, A-League | `9:02 - 3rd` at 60–55 with the summary at the same clock; finals Kings 101–102 Breakers and JackJumpers 88–94 36ers; the A-League derby 0–0 with nine yellow cards in `details`. | none |

TheSportsDB, seven runs: 132, 22, 150, 148, 26, 81 and 116 livescore rows. Ice hockey: the Canadiens
game read `P1` `11`, `P3` `18` and then `AP` at 4–3 (the shoot-out winner counted in the score, as on
ESPN), an OHL game `AP` too; the Red Wings game `AOT` 4–5; NHL intervals `BT` with no progress; the
Utah game at its puck drop had `strStatus` and `strProgress` null at 0–0 (ESPN `20:00 - 1st`); a Swiss
game `OT` `4`; `CANC` twice; WHL and AHL rows last refreshed at 05:06 London on the 15th still `P3` and
`BT` 41 hours later (skipped as before). Basketball: EuroLeague `Q4` with minutes up to `10`, finals
`FT` `10` (FIBA) and `FT` `12` (NBA), so the progress is the minute played in the quarter; a EuroCup
game `OT` `6` at 69–69; `AOT` 100–101; a FIBA Europe Cup game `POST` at 0–0 (skipped). Baseball: the
first finished rows, a Mexican Pacific game `FT` with `intHomeScore` null and 3 away, and a Venezuelan
game `FT` 2–9; live rows up to `IN8` (no half-innings or extra innings), NPB `IN2` with null scores.
The ALCS game read `IN4` with null scores and then `IN5` with only the home 3, while its
`strTimestamp` is `2026-10-13T00:00:00`, 24 hours before ESPN's first pitch (00:00Z on the 14th;
`lookuptv` gives the same date): the US date with the UTC time. Because the start looks 25 hours old,
the live row is treated as stale and a TheSportsDB-sourced MLB game would not show live; with the
real start it reads `Inning 4` and `Inning 5`. Football: an EFL Trophy game went to penalties after
90 minutes (`P` at 2–2, empty progress, last refreshed 14 minutes before the first run) and was the same
row in the second run, its last update by then 2 h 53 min old (settled as final 2–2 after 30 minutes
without a refresh); a Copa
Federación game the same (`P` 2–2, 47 minutes old). NFL: `Final/OT` in `strProgress` for the three
overtime games, refreshed for one to two days after the game and then dropped. Most `lookuptv` answers were
`{"tvevent":null}`; the Rams–Bills and Broncos–Seahawks files list 43 and 42 channels.

Parser changes: substitution moments take the commentary text; scoreboard own goals say so; the MLB
scoreboard count resets once the at-bat ends; a run that arrives as two plays is one moment; a bench
minor gives a power play; a finished fight card reads `Final` (see the table).

Checked again on this data: football minutes, cards, a second yellow (Olise, yellow then red at 73'),
goals and their sides; NHL intervals, period starts, `Final/OT` and `Final/SO` columns, goalies and
power-play strength; NBA clocks, last plays and win probability; MLB count, runners, pitcher and
batter, last plays and awards; NFL overtime finals and summary; UFC pre-fight and finish methods;
tennis sets, completed tie-breaks and server; TheSportsDB hockey intervals, overtime and shoot-out
finals, basketball quarters and overtime, baseball innings, stuck shoot-out rows, stale rows,
postponed and cancelled rows.

Confirmed now: football own goals and scored penalties on the ESPN scoreboard and summary; finished
TheSportsDB baseball rows (one with a score missing); a TheSportsDB NHL shoot-out from live to `AP`
with the final score; a UFC card whose event status reverts to scheduled once every bout is final; an
NHL bench minor; a run on a wild pitch sent as two plays.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime; an NHL power play,
empty net or shootout in progress on the scoreboard, and a shootout in the summary; a pitching change
as the MLB scoreboard's last play; TheSportsDB baseball half-innings or extra innings, and whether
`eventsday` also lists US evening games a day early (livescore and `lookuptv` do); tennis point score
and the server in a tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap; a UFC bout in
progress; live cricket; AFL/NRL live clock (off season); golf after a cut or a playoff; football extra
time and penalty shoot-outs on ESPN; a TheSportsDB penalty shoot-out after extra time and a refreshed
shoot-out row (`P` with a progress); an NBA free throw on the scoreboard with the player's name.

## Twenty-first pass (17 October)

Two ESPN passes, each with a TheSportsDB run, captured on Saturday 17 October, Brisbane time: 19:59
(09:59Z; TheSportsDB `updated` 10:59 London) and 22:58 (12:58Z; 13:57 to 13:59 London), 12 hours
after the last pass of the twentieth. 14 of the 43 files in the first pass are byte-for-byte the
twentieth's last pass and 18 in the second are the first again, among them the multi-sport header;
the nested `espn-pass.zip` is the 11 October capture again in both. In play in the first: the
A-League (Mariners 1–1 Jets, 59'), the NBL (Kings 84–69 Wildcats, `7:43 - 4th`, with its summary),
an ATP semifinal in Shanghai and three WTA matches; in the second: the Premier League (Everton 0–1
Chelsea, 67', with its summary) and three WTA matches. Finals of note: twelve NBA preseason games,
two of them `Final/OT` (Celtics 125–120 at 76ers, Bucks 128–127 at Pacers); the Mariners–Jets game
2–2; Kings 102–81 Wildcats; two NHL games. Game 4 of the ALCS (Rays at Guardians, 00:00Z) is the
first postponed game seen on ESPN. Each TheSportsDB calls folder holds that run's livescore (289 and
480 rows) and 9 and 6 new `lookuptv` files; each coverage folder's `v2-livescore-app-*` files are the
previous run's. League lists are unchanged. Every scoreboard and summary parses without errors.
Trimmed copies are in `app/src/test/resources/sports/*-real-20261017b.json` (first pass) and
`*-real-20261017c.json` (second) (`SportsRealDataSaturdayTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NBA overtime (finals) | `STATUS_FINAL`, `period` 5, `displayClock` `5:00`, `shortDetail` `Final/OT`, `altDetail` `OT`; five linescores a side (`28 32 34 21 5` against `34 24 26 31 10`); no `situation`; preseason records (`1-3`). The summary file was a regulation game, so no overtime summary yet. | none: `BOS 125–120 PHI`, `Final/OT`, an `OT` column |
| MLB postponed (scoreboard and summary) | `STATUS_POSTPONED` (`id` 6, `state` `post`, `completed` false, `Postponed`), scores `0`, no linescores, `period` 1; the note `ALCS - Game 4 Rain - Makeup on Oct 18`; the summary has the same status and no plays. | none: the scoreboard leaves the game out (as for cancelled games); a summary reads it as finished (`Postponed`, no moments), so a summary watch stops |
| Football own goal and penalties (A-League) | Mariners–Jets: two `Penalty - Scored` for the Mariners (11', 60') and at 78' an `Own Goal` by the player who took the second penalty, `team` the Jets; a `Goal - Header` (`type.id` 137) in another game. The live summary at 59' ended on the commentary for the second penalty (`Penalty conceded by …`, no `period`). | none: `O. Lavale` (home, 60') and `O. Lavale (own goal)` (away, 78') |
| Football delays (summary) | Everton–Chelsea: `Start Delay` and `End Delay` key events in pairs, one with text (`Delay in match because of an injury …`) and one without. | none: not moments; the texted ones can be last plays |
| NHL penalty shot (final summary) | `Hooking on Breakaway`, `abbreviation` `PS-Hooking`, `penaltyType` `PS`, no `penaltyMinutes`; the next play is the shot (saved), all `Even Strength`. A power-play goal later in the game (`strength` `Power Play`). | none: a penalty shot gives no power play |
| NBL (live) | `7:43 - 4th` on the scoreboard and summary, last play and a 6–0 run; TheSportsDB at the same time `Q4` `1` at 78–65 (about a minute of play behind). | none: `Q4 · 7:43` |
| Tennis (live) | Server only with a `situation`, as before; `OSTAPENKO serving for the set` at 6–5; a Ningbo match without `situation` or server. Still no point score and no tie-break in progress. | none |

TheSportsDB, two runs: Basketball (133, then 124), Ice Hockey (108, 107), Soccer (42, 244), Baseball
(5, 4) and American Football (1); every other sport answered `{"Message":"No data found"}`. Ice
hockey: a QMJHL game from 22:00Z on the 16th read `PT` at 3–3 with no progress (a shoot-out in
progress), last refreshed at 02:15 London, 3 h 15 min after the start, and still listed eight and
eleven hours later; until now such a row showed `PT` on the score bug while fresh (it is skipped as
stale here). A Belarusian `P3` `20` row from 16:00Z on the 16th still listed, 16 and 19 hours after its last refresh (skipped as before). KHL and
VHL finals `FT`; `P1` `16`, `P2` `6`. Basketball: `HT` with progress `0` (as on 11 October) and with
none, `Q2` `2` to `Q4` `10`, `NS` with progress `0`, finals `FT` `10` and `FT` with none. Baseball: a
CPBL game `IN3` with both scores null, then `FT` with only the home 3; NPB `FT` 5–8; Venezuelan rows
`NS`. No MLB row (the ALCS game was rained out). Football: stoppage up to `45+8` in the 1st half and
`90+7` in the 2nd; Everton–Chelsea `2H` `66` at 12:57Z (ESPN 67'). The `eventsday` file for the
Premier League had the same game live (`2H`, `strProgress` null, 0–1) with `strTime` shortened to
`11:30` while `strTimestamp` kept `2026-10-17T11:30:00`. NFL: `Final/OT` for the Broncos game,
still refreshed 36 hours after it. The NHL rows for Saturday are dated 17 October from 17:00Z to
23:00Z, and none of the later games (after 00:00Z on the 18th) is listed yet; the NBA and NHL
`lookuptv` files for games after 00:00Z (Lakers–Warriors and Kings–Trail Blazers 02:00Z, Mammoth–Maple
Leafs 01:00Z on the 14th) carry the same UTC date as ESPN, so the day-early start is so far seen only
on MLB postseason rows. The new `lookuptv` files all returned `{"tvevent":null}` apart from a VTB
game (one channel).

Parser changes: a TheSportsDB ice hockey `PT` row (livescore or `eventsday`) reads `SO`, as the
shoot-out column on ESPN does, instead of the bare code.

Checked again on this data: NBA and NBL clocks, finals and line scores; NHL finals, goals, goalies,
awards and strength; football minutes, stoppage time, cards, goals, penalties, own goals, sides and
substitution texts; tennis sets, server and serving for the set; TheSportsDB basketball quarters and
breaks, hockey periods and finals, baseball innings and finals with a missing score, football
minutes and half-time, stale rows, `Final/OT` progress; `eventsday` live rows.

Confirmed now: NBA overtime finals on the scoreboard; a postponed game on ESPN (scoreboard and
summary); an NHL penalty shot; a TheSportsDB hockey shoot-out in progress (`PT`, stuck); TheSportsDB
NBA and NHL games after 00:00Z on the right UTC date; an `eventsday` `strTime` without seconds.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime in progress and an
overtime summary; an NHL power play, empty net or shootout in progress on the scoreboard, and a
shootout in the summary; a pitching change as the MLB scoreboard's last play; TheSportsDB baseball
half-innings or extra innings, and whether `eventsday` also lists US evening MLB games a day early
(no MLB `eventsday` captured; livescore and `lookuptv` do for postseason rows); tennis point score
and the server in a tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap; a UFC bout in
progress; live cricket; AFL/NRL live clock (off season); golf after a cut or a playoff; football
extra time and penalty shoot-outs on ESPN; a TheSportsDB penalty shoot-out after extra time and a
refreshed shoot-out row (`P` or `PT` with a progress, or `PT` moving to `AP`); an NBA free throw on
the scoreboard with the player's name.

## Twenty-second pass (18 October)

One ESPN pass with one TheSportsDB run, captured on Sunday 18 October at 06:30 Brisbane time
(20:30Z on the 17th; TheSportsDB `updated` up to 21:29 London, the per-sport soccer file 21:31),
7.5 hours after the second pass of the twenty-first. 17 of the 43 ESPN files are byte-for-byte that
pass (the summaries of leagues with nothing live since, among them the A-League, NBL, Premier League,
NBA and MLB live summaries from earlier days); the nested `espn-pass.zip` is the 11 October capture
again. In play: four college football games (Georgia 14–7 Auburn `10:38 - 2nd` with its summary,
Tennessee 9–0 Alabama, Ole Miss 7–3 Missouri `12:05 - 2nd`, Texas Tech 21–7 Arizona State `End of
1st`) and two NHL games (Jets 0–0 Hurricanes `End of 1st` with its summary, Lightning 0–0 Canucks
`7:56 - 1st`). Finals of note: five college games, Capitals 4–2 Devils, the five Saturday Premier
League games (Everton 0–2 Chelsea with its summary, Brentford 1–2 Liverpool with two Brentford reds),
three A-League games and two NBL games. Game 4 of the ALCS is listed again as scheduled (`10/17 - 8:00
PM EDT`, 00:00Z on the 18th) and had not started; the UFC card (21:00Z) and the NASCAR race were
scheduled. The calls folder holds this run's livescore (176 rows) and six new `lookuptv` files; the
coverage folder's `v2-livescore-app-*` files are the previous run's. League lists are unchanged. No
`v1-eventsday-mlb` file came with the run, and the `eventsday` calls asked for 18 October (the PC's
date), a day ahead of the US date of the evening games. Every scoreboard and summary parses without
errors. Trimmed copies are in `app/src/test/resources/sports/*-real-20261018.json`
(`SportsRealDataQuarterBreakTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| College football quarter break (scoreboard) | Tennessee–Alabama: `STATUS_IN_PROGRESS`, `period` 1, `displayClock` `15:00` (`clock` 900), `shortDetail` `15:00 - 1st`, one line-score column (9–0); `situation` keeps the down and spot (`3rd & 6 at ALA 36`) and the drive; `lastPlay` type `End Period` (`id` 2), text `End of 1st quarter.`, `secondsLeft` 2700. Texas Tech at the same moment: `End of 1st`, `0:00`, also `STATUS_IN_PROGRESS`. | A running clock whose last play is `End of` the period shown reads as the end of that period: `End of 1st`, clock `0:00` (was `Q1 · 15:00`). The NHL shape from the eighth pass (`20:00 - 3rd` while the last play is still `End of 2nd Period`) keeps its clock |
| College football (live) | Clocks `10:38 - 2nd` and `12:05 - 2nd`, downs, spots, timeouts (2 and 3), win probability 93 and 72. The Georgia summary has the shape of the seventh pass: `drives.previous` (7) and `drives.current`, three `scoringPlays` with wallclocks from the drive plays, 53 win-probability rows. | none: `Q2 · 10:38`, `1st & 10 · AUB 36` |
| NHL (live) | Jets–Hurricanes `STATUS_END_PERIOD` `End of 1st`, `0:00`; the summary ends on `End of 1st Period` at 20:00 with both goalies on 1.000 and no strength rows; Lightning–Canucks `7:56 - 1st`. | none |
| Football finals (Premier League) | Straight reds (`type.id` 93) at `45'+8'` and `90'+4'`; Janelt booked at 63' and sent off at `90'+2'` (a second yellow, no other wording); `Goal - Header` three times, one `Penalty - Scored`; the Everton summary's `Second Half ends` at `90'+14'`. | none |
| MLB | Only the rescheduled ALCS Game 4, `STATUS_SCHEDULED`, note `ALCS - Game 4`. | none |

TheSportsDB, one run: Soccer (78), Ice Hockey (71), Basketball (23), Baseball
(3) and American Football (1). Ice hockey: the Jets game `BT` at 0–0 with no progress while ESPN read
`End of 1st`; the Lightning game `P1` `12` against ESPN's `7:56 - 1st` (the minute played, as
before); AHL, QMJHL and WHL games in the 1st and 2nd; NHL and Swiss finals `FT` with no progress; the
QMJHL `PT` row from the 16th still listed 19 hours after its last refresh, and four `P3` `20` rows
from 23:00Z on the 16th last refreshed between 02:31 and 02:45 London (all skipped as stale); a Norwegian game
`CANC`. Basketball: `Q4` `10` at the buzzer, `Q4` `6` and `1`, `Q2` `8`, finals `FT` `10` and `FT`
with none. Baseball: a Venezuelan game `IN1` with `strProgress` `IN1` (the code repeated) and only
the home 2 (away null), read as `Inning 1` with no score; two more `NS` with an empty progress. No MLB
row. Football: finals carry the last minute in `strProgress` (`90`, `90+1` to `90+15`), half-time
`45`, `45+2` and `45+3`, the 2nd half up to `90+12`. NFL: the Broncos `Final/OT` row still refreshed
44 hours after kick-off. The new `lookuptv` files for the Capitals–Devils and Jets–Hurricanes games
(11 and 4 channels) carry 17 October with the UTC times 17:00 and 19:30; the other four returned
`{"tvevent":null}`.

Parser changes: a scoreboard clock reset to the full period while the last play is the end of that
period reads `End of 1st` (see the table).

Checked again on this data: college football clocks, downs, spots, timeouts, win probability, end of
quarter and the live summary's scoring plays; NHL intervals, goalies and live clocks; football
cards, second yellows, stoppage time, goals and penalties; NBL and A-League finals; TheSportsDB
hockey intervals and minutes, stale and cancelled rows, basketball quarters and finals, baseball
innings with a missing score, football minutes, half-time and finals, `Final/OT` progress.

Confirmed now: a college football quarter break with the clock already reset; a TheSportsDB
baseball progress that repeats the inning code; two more TheSportsDB NHL games on the right UTC date.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime in progress and an
overtime summary; an NHL power play, empty net or shootout in progress on the scoreboard, and a
shootout in the summary; a pitching change as the MLB scoreboard's last play; a quarter break with
the clock reset in a live summary; TheSportsDB baseball half-innings or extra innings, and whether
`eventsday` also lists US evening MLB games a day early (no MLB `eventsday` captured yet, and from a
Brisbane morning the PC's date is the next US day; livescore and `lookuptv` do for postseason rows);
tennis point score and the server in a tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap;
a UFC bout in progress; live cricket; AFL/NRL live clock (off season); golf after a cut or a playoff;
football extra time and penalty shoot-outs on ESPN; a TheSportsDB penalty shoot-out after extra time
and a refreshed shoot-out row (`P` or `PT` with a progress, or `PT` moving to `AP`); an NBA free throw
on the scoreboard with the player's name.

## Twenty-third pass (18 October, 07:10)

One ESPN pass with one TheSportsDB run, captured on Sunday 18 October at 07:10 Brisbane time
(21:10Z on the 17th; TheSportsDB `updated` up to 22:09 London), 40 minutes after the twenty-second.
This is the first capture from the cloud schedule with the extended script: 44 ESPN files (CFL,
MLS, EFL Cup, Europa League, men's college hockey, LPGA and PFL added; the CFL summary answered 502)
and the MLB `eventsday` for 18 October and for the previous day. In play: the same four college
football games late in the 2nd quarter (Georgia 14–17 Auburn `0:27 - 2nd` with its summary), two
NHL games in the 2nd (Jets 2–1 Hurricanes with its summary, Lightning 0–2 Canucks), two college
hockey games (Clarkson 1–0 Canisius `End of 1st`, UConn–Colorado College `16:09 - 1st`), MLS
(Minnesota 0–2 St. Louis, 29', with its summary) and the first bout of the UFC Fight Night card.
Every scoreboard and summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261018b.json` (`SportsRealDataWalkoutTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NHL penalty without the infraction (scoreboard) | Lightning–Canucks `14:25 - 2nd`: `lastPlay` type `Penalty Shot Infraction` (`id` 143), `abbreviation` `Closing Hand on the Puck`, `penaltyMinutes` `2`, `penaltyType` `Minor`, text `Liam Ohgren Minor`. The same shape is in the 10 October summary twice; every other penalty text names the infraction (`William Borgen Tripping against Noah Cates`). `situation` still holds only `lastPlay`, so the scoreboard has no power-play flag. | A penalty text that ends in the bare penalty type takes the infraction from `abbreviation` (`Liam Ohgren Closing Hand on the Puck`), on the scoreboard and in the summary's last plays |
| NHL power play (live summary) | Jets–Hurricanes: a power-play goal at 8:19 of the 2nd (`strength` `Power Play` on the plays before it), then `Even Strength` again; the summary (11:17 elapsed) runs about 15 seconds behind the scoreboard (`8:28 - 2nd`). | none |
| UFC card at walkouts | Event `STATUS_IN_PROGRESS`; the first bout `STATUS_FIGHTERS_WALKING` (`state` `in`, `Walkouts`, `period` 0, `displayClock` `-`), the other twelve scheduled (seven at 21:00Z, six at 00:00Z). | none: the card is live with `Walkouts`, no round; the six 00:00Z bouts are the main card |
| College hockey and MLS (live) | `STATUS_END_PERIOD` `End of 1st`, `16:09 - 1st`; MLS `STATUS_FIRST_HALF` `29'` with goals and yellow cards in the summary. The college hockey summary has no plays. | none |
| College football (live) | Clocks `0:27`, `0:21`, `1:57` and `3:49` in the 2nd; no two-minute warning in the summary or as a last play. | none |
| Finals | WTA `STATUS_RETIRED`; cricket (Australia 278/8 against England 166 in 39.1 overs) final with no status name; a CFL final; Europa League and A-League finals. | none |

TheSportsDB, one run: Soccer (60), Ice Hockey (61), Basketball (8), Baseball (3) and American
Football (1). The MLB `eventsday` for 18 October lists Game 4 of the ALCS (`dateEvent`
`2026-10-18`, `strTime` `00:00:00`, `strTimestamp` `2026-10-18T00:00:00`, `dateEventLocal`
`2026-10-17`, `strTimeLocal` `20:00:00`) and Game 5 (`2026-10-18T21:00:00`, local 17:00 the same
day); the list for 17 October is empty. So `eventsday` dates a US evening game by its UTC date and
gives the US date separately: the day-early start seen in livescore and `lookuptv` rows for postseason
games does not come from `eventsday`. `SportsDbEvents` reads the right start from `strTimestamp`,
and `SportsDbLive.merge` applies a livescore row to that fixture's start without reading the row's
own timestamp, so a live MLB row is not treated as stale. No MLB livescore row yet (Game 4 was still to start). Ice hockey: Jets–Hurricanes
`P2` `11` at 1–1 while ESPN already had 2–1; the QMJHL `PT` row from the 16th is still listed;
five `P3` `20` rows. Baseball: a Venezuelan game `IN4` with progress `IN4`. Football: a stuck `P`
row with an empty progress, finals up to `90+15`.

Parser changes: hockey penalty texts that end in the bare penalty type name the infraction.

Checked again on this data: NHL and college hockey clocks, intervals and power-play strength;
college football clocks and last plays; MLS minutes, goals and cards; UFC card states and the main
card split; tennis retirements; TheSportsDB hockey periods, baseball innings, football minutes,
`eventsday` starts.

Confirmed now: a UFC card at walkouts; `eventsday` gives US evening MLB games their UTC date (with
`dateEventLocal` for the US date); live college hockey and MLS on ESPN.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime in progress and an
overtime summary; an NHL power play, empty net or shootout in progress on the scoreboard (the
scoreboard carries only the penalty as its last play), and a shootout in the summary; a pitching
change as the MLB scoreboard's last play; a quarter break with the clock reset in a live summary;
TheSportsDB baseball half-innings or extra innings, and a live TheSportsDB MLB postseason row merged
into its `eventsday` game; tennis point score and the server in a
tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap; a UFC bout in progress (round and
clock); live cricket; AFL/NRL live clock (off season); golf after a cut or a playoff; football extra
time and penalty shoot-outs on ESPN; a TheSportsDB penalty shoot-out after extra time and a refreshed
shoot-out row (`P` or `PT` with a progress, or `PT` moving to `AP`); an NBA free throw on the
scoreboard with the player's name.

## Twenty-fourth pass (18 October, 10:28)

One ESPN pass with one TheSportsDB run, captured on Sunday 18 October at 10:28 Brisbane time
(00:28Z; TheSportsDB `updated` up to 01:27 London), 3 h 18 min after the twenty-third, from the
schedule. 110 files; the CFL summary answered 502 again. In play: Game 4 of the ALCS (Guardians
1–0 Rays, `Top 2nd`, with its summary), six NHL games (Panthers 8–3 Kraken `7:18 - 3rd` with its
summary), 18 college hockey games, five college football games in the 2nd quarter, five MLS games
around half-time, the final LPGA round, and the UFC main card between bouts. Every scoreboard and
summary parses without errors. Trimmed copies are in
`app/src/test/resources/sports/*-real-20261018c.json` (`SportsRealDataMainCardTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| College football red zone without a down (scoreboard) | Miami 14–7 Florida State `2:00 - 2nd`: `lastPlay` `Timeout Florida State, clock 02:00` (type `Timeout`); `down` 0, `distance` 10, `yardLine` 15, `isRedZone` true, no down text and no `possession`; the drive is `1 play, 0 yards` with no result. Across every capture `down` 0 has come three times, always with `isRedZone` true and never with down text or possession (the Kentucky timeout of 11 October was the same shape). | The red-zone flag needs a down of 1 or more (was 0 or more), so the bug no longer reads `Red zone` alone during these timeouts |
| UFC main card between bouts | Event `STATUS_IN_PROGRESS`; eight bouts `STATUS_FINAL`, the ninth `STATUS_PRE_FIGHT`, four scheduled. Finished bouts carry `period` and `displayClock` as the end of the fight (`3`, `4:19`; `2`, `0:47`). Six have `Unofficial Winner Submission` (20) or `Unofficial Winner Kotko` (21) in `details`; two do not yet: Anheliger–Koslow (`3`, `4:31`) has only `Fight Over` (19), and Landwehr–Nallo (`2`, `0:47`) `Results` (23) and `Fight Over`, though `winner` is set on both. | none: winner, round and time with no method for those two |
| College hockey intervals and overtime | `STATUS_END_PERIOD` `End of 2nd` and `End of 3rd` (the latter at 8–2 and 2–4, games over but not yet final); one `STATUS_IN_PROGRESS` with `displayClock` `0:00` and `shortDetail` `End of 2nd`; a `Final/OT`. No plays in the live summary. | none: `End of 2nd`, `End of 3rd`, `Final/OT` |
| MLB (live) | `Top 2nd`, 0–0 count, no runners, `lastPlay` `Foster Griffin pitches to Liam Hicks` (`Start Batter/Pitcher`, `summaryType` `A`). No pitching change yet. | none: `▲2`, `0 outs · 0–0` |
| NHL (live) | Six games; the Panthers summary has 33 `Power Play` and 10 `Shorthanded` plays, all ended by the capture; scoreboard last plays `Off-side`, `TV Timeout`, shots and blocks. No penalty, power play or empty net on the scoreboard. | none |
| MLS (live) | `45'`, `45'+2'`, `45'+3'` (`STATUS_FIRST_HALF`) and `HT` (`STATUS_HALFTIME`). | none |

TheSportsDB, one run. The MLB `eventsday` for 18 October now has Game 4 live: `strStatus` `IN1`,
`strProgress` null, 0–0 (ESPN at the same moment `Top 2nd`, 1–0), with the same start as before
(`2026-10-18T00:00:00`). The baseball livescore (13 rows) does not list Game 4 at all; it has Game 5
(`NS`, `2026-10-18T21:00:00`, the right UTC time) and Mexican Pacific and Venezuelan games (`IN1`
to `IN7`, one with only the away score). So a live MLB game from TheSportsDB comes from `eventsday`
with the right start and reads `Inning 1`; the livescore merge leaves it as it is.

Parser changes: the college football (and NFL) red-zone flag needs a down of 1 or more.

Checked again on this data: college football clocks, timeouts and win probability; UFC card
states, finishes, end rounds and clocks, main card split; college hockey intervals and overtime
finals; MLB inning, count and last play; NHL clocks and strength; MLS minutes, stoppage time and
half-time; TheSportsDB baseball innings with a missing score, `eventsday` live rows.

Confirmed now: a live TheSportsDB MLB postseason row (in `eventsday` only, right start); finished
UFC bouts before the method is posted; college hockey overtime finals and an interval reported as
in progress.

Still unconfirmed: the NFL two-minute warning on the scoreboard; NBA overtime in progress and an
overtime summary; an NHL power play, empty net or shootout in progress on the scoreboard (the
scoreboard carries only the penalty as its last play), and a shootout in the summary; a pitching
change as the MLB scoreboard's last play; a quarter break with the clock reset in a live summary;
TheSportsDB baseball half-innings or extra innings; tennis point score and the server in a
tie-break; NASCAR stages; F1 gaps, pit stops and fastest lap; a UFC bout in progress (round and
clock), and the finish method arriving later for a bout first posted without one; live cricket;
AFL/NRL live clock (off season); golf after a cut or a playoff; football extra time and penalty
shoot-outs on ESPN; a TheSportsDB penalty shoot-out after extra time and a refreshed shoot-out row
(`P` or `PT` with a progress, or `PT` moving to `AP`); an NBA free throw on the scoreboard with the
player's name.

## Twenty-fifth pass (18 October, 11:27)

One ESPN pass with one TheSportsDB run, captured on Sunday 18 October at 11:27 Brisbane time
(01:27Z), an hour after the twenty-fourth, as an extra run for the UFC main card. 110 files; the CFL
summary answered 502 a third time. In play: Game 4 of the ALCS (`Bot 5th`, 1–1, with its summary),
eight NHL games, college hockey, five college football games (two at half-time), ten MLS games, the
final LPGA round and the UFC card, again between bouts. Every scoreboard and summary parses without
errors. Trimmed copies are in `app/src/test/resources/sports/*-real-20261018d.json`
(`SportsRealDataHalfTimeDownTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| College football half-time with a down (scoreboard) | BYU 21–10 Notre Dame and Houston 14–3 Oklahoma State, `STATUS_HALFTIME` `Halftime`: `lastPlay` `End of 2nd quarter.` (`End Period`), but `situation` still holds the last snap's `down` 2 and 3, `downDistanceText` `2nd & 10 at BYU 5` and `3rd & 11 at HOU 28`, `possessionText` and `possession`. Earlier half-times (NFL and college) had no down text. | At half-time the down line and possession are left out, so the bug reads `Halftime` alone (was `Halftime` with `3rd & 11 · HOU 28` and the ball marker) |
| UFC card at the next walkouts | Ten bouts final, the eleventh `STATUS_FIGHTERS_WALKING`, two scheduled; no bout caught in a round in either extra run. An hour after the twenty-fourth pass, Anheliger–Koslow and Landwehr–Nallo still have no `Unofficial Winner …` detail, and Jourdain–Cuamba (`2`, `2:48`) joined them; the other seven have one. | none: winner, round and time with no method |
| College football timeout | Miami 14–14 Florida State `8:10 - 3rd`, `Timeout Florida State`, `down` 0 and `isRedZone` false this time. | none |
| College hockey | `End of 3rd` at 1–5, 2–2, 3–2 and 5–0, two `Final/OT`. | none |
| MLB, NHL, MLS | `Bot 5th`, 1–1, `Start Batter/Pitcher`; NHL clocks in all three periods, no penalty or empty net on the scoreboard; MLS `85'` to `89'`, `HT`, `45'+2'`. | none |

TheSportsDB, one run: the MLB `eventsday` still had Game 4 at `IN1`, 0–0, an hour after the first
pitch (ESPN `Bot 5th`, 1–1); the row was not refreshed between the two runs. At 12:28 it had moved
to `IN7`, 1–2, while ESPN was in the `Bot 7th` at 2–5: the row is refreshed, but late and with the
score behind. The day schedule has no `updated` time, so the app shows what the row last said; ESPN
is the better source for MLB. The baseball livescore again listed only Game 5.

Parser changes: a football half-time drops the stale down line and possession.

Checked again on this data: college football half-time, clocks, timeouts and red zone; UFC
finishes, end rounds and clocks; college hockey intervals and overtime; MLB inning and count; NHL
clocks; MLS minutes and stoppage time; TheSportsDB `eventsday` live rows.

Confirmed now: football half-time with the last down still in `situation`; UFC finishes still
without a method an hour later (a 12:28 capture: still none for those three, while the next two
finishes and a decision had theirs straight away); TheSportsDB `eventsday` MLB rows that refresh
late during play (12:28: `IN7` at 1–2 against ESPN's `Bot 7th` at 2–5).

Still unconfirmed: unchanged from the twenty-fourth pass, apart from the UFC method now known to stay
missing for at least an hour (whether it ever arrives is still open).

## Twenty-sixth pass (18 October, 12:33 to 12:43)

One full ESPN and TheSportsDB capture at 12:33 Brisbane time (02:33Z) with the UFC main event
(Buckley v Malott, five rounds) in its first round, then the UFC scoreboard alone every 45 seconds
until 12:43. Nothing new in the other sports five minutes after the 12:28 run. Trimmed copies of the
main event at each new state (the last two bouts of the card) are in
`app/src/test/resources/sports/espn-ufc-scoreboard-real-20261018e.json` to `…k.json`
(`SportsRealDataMainEventTest`).

| Time | Bout `status` | Note |
|---|---|---|
| 12:33:12 | `STATUS_IN_PROGRESS_2` (`id` 31, `state` `in`), `period` 1, `displayClock` `3:03`, `shortDetail` `R1, 3:03` | The event stays `STATUS_IN_PROGRESS`. The clock counts down: `3:03`, `2:32`, `1:30`, `0:59` over the next two minutes, so it is the time left in the round |
| 12:35:57 | `STATUS_IN_PROGRESS_2`, `R1, -`, `displayClock` `-` | At the bell, before the break status |
| 12:36:43 | `STATUS_END_OF_ROUND` (`id` 142), `End R1`, clock `-` | The one-minute break |
| 12:37:28 | `STATUS_IN_PROGRESS_2`, `R2, 4:40` | Round 2 |
| 12:40:30 | `STATUS_END_OF_ROUND`, `End R2`, clock `2:28`; `details` `Fight Over` first | A stoppage 2:32 into round 2; the bout stays `End R2` (`state` `in`, no `winner`) for over two minutes |
| 12:42:46 | `STATUS_FINAL`, `period` 2, `displayClock` `2:28`; `winner` set; `Unofficial Winner Kotko` | The finish first carries the time left |
| 12:43:32 | `STATUS_FINAL`, `displayClock` `2:32` | Corrected to the time elapsed, as every earlier finish time was (`0:27`, `4:28`, a decision `5:00`) |

Parser changes: a live bout keeps its clock (`FightBout.clock`, also in the cache) while a round is
running, and the score bug reads `Round 1 of 5 · 3:03`; at the bell (`-`) and in the break
(`STATUS_END_OF_ROUND`) it reads `Round 1 of 5` as before. A finish shows the time ESPN gives, so for
about a minute after the result it can show the time left (`R2 2:28`) before the correction.

Confirmed now: a UFC bout in progress (round and clock, counting down), the break between rounds,
the stoppage reported as the end of the round before the result, and the corrected finish time.

Still unconfirmed: as in the twenty-fourth pass, without the UFC bout in progress; whether a UFC
finish posted without a method ever gets one is still open.

## Twenty-seventh pass (18 October, 13:17)

One ESPN pass with one TheSportsDB run at 13:17 Brisbane time (03:17Z), the last extra run for the
UFC card. 110 files; the CFL summary answered 502 again. Every scoreboard and summary parses without
errors. Trimmed copies are in `app/src/test/resources/sports/*-real-20261018l.json` and `…m.json`
(`SportsRealDataDoubleOvertimeTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| College football double overtime (final) | Miami 43–35 Florida State: `STATUS_FINAL`, `period` 6, `shortDetail` `Final/2OT`, `altDetail` `2OT`; six line-score columns (`7 7 0 14 7 8` against `0 14 7 7 7 0`). | none: `Final/2OT`, columns `OT` and `2OT` |
| UFC card (final) | The event's own status is `STATUS_FINAL` this time (on 11 October it went back to scheduled). All 13 bouts final; the main event `2`, `2:32`, `Unofficial Winner Kotko`. The three finishes posted without a method (Anheliger–Koslow, Landwehr–Nallo, Jourdain–Cuamba) still have none, two and a half to four hours after the bouts. | none: `Final`; those three show the winner, round and time only |
| College football quarter break | Colorado–Utah `15:00 - 1st` with `End of 1st quarter.` as the last play. | none: `End of 1st` (twenty-second pass) |
| MLB, NHL, MLS | ALCS Game 4 `Bot 9th`, 3–7; NHL overtime and shoot-out finals; MLS in both halves. | none |

TheSportsDB: the MLB `eventsday` row was still `IN7`, 1–2, 50 minutes after the 12:28 run (ESPN
`Bot 9th`, 3–7), so its refreshes are rare as well as late.

Confirmed now: a college football double-overtime final; a finished UFC card whose event status is
final; UFC finishes that stay without a method for hours.

Still unconfirmed: as in the twenty-sixth pass.

## Twenty-eighth pass (18 October, 22:40 to 22:45)

Live cricket, captured on request at 22:40 and 22:45 Brisbane time (12:40Z and 12:45Z). The fixed
series in the capture script (`cricket/8676`, the Australia–England series) had nothing live, so the
matches were found through ESPN's cricket score panel (`/cricket/scorepanel`, every series with a
match today: Ranji Trophy, domestic T20s, an ODI tri-series, a Test and women's T20Is). The capture
script now reads that panel and fetches the series scoreboard and summary of up to four
international matches (ODI, Test, T20I, women's) that are live or finished. Trimmed copies are in
`app/src/test/resources/sports/espn-cricket-*-real-20261018n.json` to `…p.json`
(`SportsRealDataLiveCricketTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| ODI chase (scoreboard) | Pakistan v Sri Lanka (`cricket/1550706`, ODI no. 5037, day/night): status `state` `in` with no `name`, `detail` `Live`, `period` 2, `displayClock` `0'`; `summary` `Sri Lanka require 301 runs`. Scores `305` (all out, 49.5 overs) and `5/0 (1.4/50 ov, target 306)`, while the line score already had `1.5` overs. | none: `PAK 305 · SL 5/0`, `1.5 ov`, `Need 301 from 289`; the period text is `Live` (the `0'` clock is dropped) |
| Test, first innings (scoreboard) | South Africa v Australia (`cricket/24203`): `SA 20/2 (12.2 ov)`, Australia with no score, `summary` `Australia won toss & fielded`, unchanged over five minutes (a rain delay: see the thirtieth pass). | none then: `SA 20/2`, `12.2 ov` |
| Women's T20I final | Australia 165/8 (18.5 of 20 overs, target 163) beat New Zealand 162/2: `AUS Women won by 2 wkts (7b rem)`. | none |
| Live cricket summary | No plays or commentary at all: `header` (innings in the line scores with `overs`, `target`, `isBatting`, partnerships and fall of wickets), `matchcards` (batting, bowling, partnerships), `notes` (hours of play), `rosters`. `situation` holds only `odds`. | none: innings only, no last plays or moments |

TheSportsDB had no live cricket in its livescore.

Confirmed now: live ODI and Test scoreboards and summaries on ESPN; the summary carries no
ball-by-ball plays.

Still unconfirmed: as in the twenty-sixth pass, without live cricket. The app offers only the Big
Bash (TheSportsDB) for cricket; international series have no league entry (user decision).

## Twenty-ninth pass (18 October, 23:28)

The first scheduled run with the full capture (every league the app offers and every league ESPN
lists): 543 files at 23:28 Brisbane time (13:28Z), 39 live summaries; the CFL summary answered 502.
In play: the Premier League, La Liga, Serie A, Ligue 1, the Süper Lig, the WSL and the women's League
Cup, 14 other football leagues, the Champions Cup and URC rugby, and seven cricket matches (the
Pakistan–Sri Lanka ODI at 45/1 after 10.5 overs). Every scoreboard parses without errors. Trimmed
copies are in `app/src/test/resources/sports/espn-*-scoreboard-real-20261018q.json`
(`SportsRealDataShootoutTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| Football final on penalties (scoreboard) | 23 `STATUS_FINAL_PEN` finals across ESPN's cup and play-off leagues: `shortDetail` `FT-Pens`, `period` 5, `displayClock` `120'`, `completed` true; each competitor has `shootoutScore` (EFL Trophy, Charlton 2–2 Tottenham U21, `7` and `6`) and `winner`. | The status reads `FT-Pens · CHL 7–6` (the winner's abbreviation and the shoot-out score) instead of `FT-Pens` alone, so the winner shows whichever side the score bug puts first |
| Football final after extra time | 22 `STATUS_FINAL_AET` finals: `AET`, `period` 4, `120'`. A Copa del Rey tie ended 3–3 `AET` with `winner` true for the away side and no `shootoutScore` on either competitor, in the scoreboard or the summary. | none: `AET`, the score as given |
| Other statuses | `STATUS_SUSPENDED` (Chilean cup, left out like postponed games), `STATUS_CANCELED` (football, LIV), `STATUS_TBD` (seven rugby fixtures). | none |
| Live cricket | Seven matches in the score panel (an ODI, a Test, women's T20s and domestic one-dayers), several not yet started at the score level (no score text). | none |

TheSportsDB: one football row in extra time (`ET`, `98`), as on 12 October.

Parser changes: a football final decided on penalties names the winner and the shoot-out score.

Confirmed now: ESPN football finals after extra time and on penalties, with the shoot-out score.

Still unconfirmed: as in the twenty-sixth pass, with football extra time and penalties narrowed to a
game in extra time or in a shoot-out live on ESPN, and the summary of a shoot-out.

## Thirtieth pass (18 October, 23:38)

South Africa v Australia (Test, day 1) captured on request during a rain delay at 23:38 Brisbane
time (13:38Z). Trimmed copy in `app/src/test/resources/sports/espn-cricket-scoreboard-real-20261018r.json`
(`SportsRealDataRainDelayTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| Cricket rain delay (scoreboard and summary) | `state` `in`, `detail` and `shortDetail` `Live`, but `type.id` `101` and `description` `Match delayed by rain`; `session` `Day 1`, `summary` `Australia won toss & fielded`; the score unchanged at `20/2 (12.2 ov)`. The summary's `notes` log each stoppage (`Rain: South Africa - 20/2 in 12.2 overs …`, `Tea: …`, `Wet Ground: …`). The 22:40 and 22:45 captures of the twenty-eighth pass were the same delay. In normal play `type.id` is `0` and `description` `Live`. | A live cricket match whose status description is not `Live` shows it (`Match delayed by rain`) as its status, and the score bug's third line reads it in place of the run chase (`SportsDetail.Cricket.stoppage`, also in the cache) |

Confirmed now: a cricket rain delay on ESPN.

## Thirty-first pass (19 October, 04:28)

Scheduled full capture on Monday 19 October at 04:28 Brisbane time (18:28Z on the 18th): 559 files.
In play: seven NFL games at the end of the 2nd quarter (two at half-time), 23 women's college
volleyball matches, ten women's college field hockey games, the Champions Tour, NWSL, the Eredivisie,
the Saudi league and 70 other football games. Trimmed copies are in
`app/src/test/resources/sports/espn-college-*-scoreboard-real-20261019.json`
(`SportsRealDataNewSportsTest`).

| Shape | What the real response showed | Parser change |
|---|---|---|
| NFL end of the 2nd quarter (scoreboard) | Last plays `Timeout #1 by CHI at 01:02.  10-second runoff avoided.`, `Official Timeout at 02:42.`, `(Andy Borregales Kick)` (`Extra Point Good`), ` (No Huddle) A.Rodgers spiked the ball to stop the clock.` (a leading space), `4th & Goal at NYG 2` with `isRedZone` true; half-time with both sides' timeouts back at 3. The two-minute warning was in the three live summaries (`Two-Minute Warning`) but not a scoreboard's last play. | none: the spike reads `A.Rodgers spiked the ball to stop the clock.`, the kick `Extra Point Good (Andy Borregales Kick)`, `Red zone` on 4th and goal |
| College volleyball (no league in the app) | `3rd Set`, `period` 3, `displayClock` `0:00`; `score` is sets won (`2`), `linescores` the points per set (`29 25 10` against `27 22 7`). | none: the generic parser reads `2–0`, `3rd Set` and a column per set |
| College field hockey (no league in the app) | `55:54 - 4th` with the clock rising through 60 minutes, `End of 3rd` at `45:00`, a column per quarter. | none: `55:54 - 4th`, `End of 3rd` |
| Football | `STATUS_IN_PROGRESS` (instead of `STATUS_FIRST_HALF`) with `30'` in two Eredivisie and other games. | none |

Confirmed now: college volleyball and field hockey shapes (for a later league entry; user decision).

Still unconfirmed: as in the twenty-ninth pass.
