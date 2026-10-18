# Nuvio IPTV — handover

Branch: `iptv/player-binding` (includes the fork release `1.1.0-nt.5`, merged on 12 October 2026). Only this branch is
worked on; `iptv/wip` and `nuvio-test` were tidied on 9 October with the user's permission.
Related: [progress](IPTV-PROGRESS.md), [draft release notes](IPTV-RELEASE-NOTES-DRAFT.md),
[device test checklist](IPTV-DEVICE-TEST-CHECKLIST.md), [sports design pass 2](IPTV-SPORTS-DESIGN-PASS-2.md),
[sports data fields](IPTV-SPORTS-DATA-FIELDS.md), [sports data scripts](IPTV-SPORTS-DATA-SCRIPTS.md).
Older sections further down are history; read "Start here" first.

## Start here — next session (state on 17 October 2026, Brisbane)

Read this section, then "Rules for this branch", "Code map" and "Target devices". Everything
under "Earlier session notes" and the dated sections is history kept for reference.

### State

- 12 October: the fork release `1.1.0-nt.5` (tag; the fork's `main`, upstream 1.1.0-beta.5
  plus the Nuvio Arc work) is merged into this branch with a merge commit (no rebase: the
  branch history is never rewritten). Nine conflicts, all resolved keeping both sides:
  Live TV next to Calendar in the sidebar and root routes (`appRootRoutes` in
  `MainActivity.kt` adds `Screen.IptvLive` when `FEATURE_IPTV_ENABLED`), the Calendar route
  next to the IPTV routes in `NuvioNavHost.kt`, Live TV Home rows after Coming up
  (Classic, as in Grid), the Live TV row key guard before the new vertical-move code
  (Modern), the playback-purpose and IPTV user-agent rules next to the new slow-start
  server handling in `PlayerPlaybackNetworking`/`PlayerMediaSourceFactory`, no watch party
  for recordings next to the new Speed button. The prototype manifest names the ten new
  launcher icon choices "Nuvio IPTV Prototype" too, and the recording notification uses the
  fork's notification icon `ic_arc_notification` (the release removed `drawable/ic_launcher`). Host tests unchanged in count and
  green on the merge; the rest of the upstream changes (V2 settings, player, calendar,
  media servers, watch party) need a regression look on the device.
- Work since the merge (all host-tested and CI-built, none device-tested yet):
  - Second pass over everything not yet tried on a device (`e7e1d17b5..85fe99dce`): three
    read-only reviews found 25 defects, fixed in `74ab681fc` (Sport and multiview screens),
    `7b7c32e02` (recordings and catch-up) and `91e1e7f1b` (sport data, sport recordings
    through overtime, unused strings removed). Checklist 7n.
  - Sports captures 13 to 19 (12 October): `011204b82` (06:42), `2d693cada` (08:01, 7o),
    `9d1c91585` and `98d520804` (09:40, NHL empty net, 7p), `0238ba866` and `11b487e0b`
    (11:26, baseball, 7q), `14945dbef` (11:43, basketball, 7r), `1e539a53a` (12:06, tests
    only), `8260658fc` (21:03, TheSportsDB baseball innings, 7t).
  - Live player `a7266ce95` (7s): steady behind-live figure on HLS, immediate rejoin behind
    the live window, local timeshift pause held to the buffer. Suggested by reading an
    unmerged upstream player change; our own design, no code taken.
  - Capture batch 20 (13–16 October, seven ESPN passes with seven TheSportsDB runs)
    `411ad607f` (7u): substitution text, own goals, MLB count after an at-bat, duplicate run
    moments, bench minors, finished fight cards.
  - Capture batch 21 (17 October, 19:59 and 22:58, two ESPN passes with two TheSportsDB
    runs) in `b4af47e5b` (7v): TheSportsDB hockey shoot-out `PT` reads SO;
    NBA overtime finals, a postponed MLB game, an NHL penalty shot and an own goal by a
    penalty scorer confirmed with no change.
  - Capture batch 22 (18 October 06:30, one ESPN pass with one TheSportsDB run) in `8724e01f3`
    (7w): an ESPN scoreboard clock reset to the full period at a quarter
    break reads "End of 1st"; college football live, NHL intervals and two more NHL games on
    the right UTC date confirmed with no change.
  - Capture 23 (18 October 07:10, the first scheduled cloud capture) in the commit after
    `43ae401` (7x): an NHL penalty whose text is only the player and "Minor" names the
    infraction; a UFC card at walkouts, live college hockey and MLS confirmed with no change;
    the MLB `eventsday` dates evening games by UTC, which closes the day-early start question.
  - Capture 24 (18 October 10:28, scheduled) in the commit after `7674c24` (7y): the red-zone
    flag needs a real down (timeouts with `down` 0 no longer read "Red zone"); a live MLB
    row from TheSportsDB (`eventsday` only), the UFC main card between bouts and college
    hockey intervals and overtime confirmed with no change.
  - Capture 25 (18 October 11:27, extra run for the UFC main card) in the commit after
    `ac9beae` (7z): a football half-time no longer shows the last down and possession; UFC
    finishes without a method, and a TheSportsDB MLB row stuck at `IN1`, noted.
  - Capture 26 (18 October 12:33 to 12:43, the UFC main event sampled every 45 s) in the
    commit after `c90d05e` (7aa): a live UFC bout shows the time left in the round on the
    score bug ("Round 1 of 5 · 3:03").
  - Capture 27 (18 October 13:17, the last UFC extra run) in the commit after `29a199b`:
    tests only (a college double-overtime final, the finished UFC card).
  - Capture 28 (18 October 22:40 to 22:45, live cricket on request) in the commit after
    `f9279d6`: tests only (a live ODI chase, a Test and a women's T20I final read correctly);
    the capture script now finds live international cricket through ESPN's score panel.
  - Open question for the user (7s.7): does a 5-minute pause with Safety buffer off leave TS
    channels behind live? If so, offer Return to live there too.
- All work is committed and pushed to `iptv/player-binding`. Build for the next device pass:
  CI debug green at `8724e01f3` (run 39068602774; `b4af47e5b`, run 39036483344, `411ad607f`, run 38988428742, `8260658fc`,
  run 38242468867, `a7266ce95`,
  run 38201561490, `1e539a53a`, run 38196253340, `98d520804`, run 38186968485, `2d693cada`,
  run 38180390637, and `91e1e7f1b`, run 38178089678, were the builds before), app "Nuvio
  IPTV Prototype", file
  `app-iptvPrototype-arm64-v8a-debug.apk`; the merge alone was green at `0d1d90ab3` (run
  38175520520). Minified release (R8) was last built at `c1685393a` (run 38058607115),
  before the merge; build it again before shipping.
- Host tests at that commit: core 1032, data 305, androidTest compiles (core 1052 now).
  Translations: all 40 locales, `check_translations.py` prints `problems 0`.
- Device testing: the user's last checklist pass was on 10 October, evening (`e7e1d17b5`;
  7e and 7d passed apart from the findings fixed in 7f); on 11 October they recorded ~3 h to
  an SMB NAS on `f79646c53` (findings fixed in 7i). Everything in checklist sections 7f to
  7w is built and host-tested but not yet tried on a device; the user was about to test
  `85fe99dce` (7m to 7f) when the merge was asked for, so findings may refer to that build.
- A printable test sheet for the current build is made from the checklist (see "How to
  build and check" below); the user prints it for each device pass.

### Next, in order

1. Device pass on `8724e01f3` (checklist 7w, 7v, 7u, 7t, 7s, 7r, 7q, 7p, 7o, 7n, then 7m, 7l,
   7k, 7j, 7i, 7h, 7g, 7f, plus sections 1 and 2 as a regression check after the merge;
   `b4af47e5b` differs only in 7w). Findings on `85fe99dce` are still valid unless 7n to 7w
   or the second-pass fixes changed that area. Record the
   findings in a new "Device findings" section below, fix them, update the release notes in
   the same commits, translate new strings, build, send the run link and a new test sheet.
2. Keep processing the user's ESPN/TheSportsDB captures (process below). The thirteenth
   pass (12 October 06:42) showed NFL overtime, end of quarter and a real red zone, and a
   football second yellow (a yellow, then a red for the same player, no other wording).
   The fourteenth pass (12 October 08:01) showed NFL half-time, an NFL overtime tie
   (`Final/OT`) and the NFL summary after half-time, an NHL end of period live, and
   TheSportsDB `Halftime`, `Final/OT` and a stuck penalty shoot-out (`P`). The fifteenth
   pass (12 October 09:40) showed an NHL empty net (summary `onIce` only: six skaters and no
   goalie for the trailing side), the last NFL minute after a turnover on downs (the
   two-minute warning only in the summary plays), NBA 2nd quarter live with team fouls, and
   a finished NASCAR race (`winner` false on every car). The sixteenth pass (12 October
   11:26) showed an MLB full count on the scoreboard, a balk in the summary (two plays with
   the same text), challenged pitch calls, an NFL defensive touchdown, an NHL power play
   ending on time, and TheSportsDB baseball rows (none live). The seventeenth pass (12
   October 11:43) showed NBA scoreboard free throws without the player (three of three so
   far), a bare `vs.` tip-off in the summary, an NFL timeout as the scoreboard's last play
   (credited to the side with the ball) and an MLB home run moment. The eighteenth pass (12
   October 12:06) showed MLB pitching changes (mid-inning and between innings; the at-bat
   start is rewritten in place with the new pitcher), NFL timeouts back to three after
   half-time, TheSportsDB football extra time (`ET` with the minute) and a hockey interval
   (`BT`); no parser change was needed. The nineteenth pass (12 October 21:03) showed an
   NFL overtime win (`Final/OT` with a 7–0 overtime), the NLCS Game 1 final (pinch hitter
   and runner plays, no root `situation`), the NASCAR `winner` flag set hours after the race,
   and the first live TheSportsDB baseball rows (`IN4`, inning only, scores sometimes null),
   now shown as "Inning 4". The twentieth pass (13–16 October, seven ESPN passes paired
   with seven TheSportsDB runs) showed football own goals and penalties on ESPN, finished
   TheSportsDB baseball rows, a TheSportsDB NHL shoot-out going from live to `AP`, and
   TheSportsDB dating US evening MLB games a day early (the live row is then treated as
   stale). The twenty-first pass (17 October, 19:59 and 22:58) showed NBA overtime finals
   (`Final/OT`, five columns), the first postponed ESPN game (ALCS Game 4, left out of the
   scoreboard; the summary reads Postponed), an NHL penalty shot (`PS`, no power play), an
   A-League own goal by the penalty scorer, and a TheSportsDB hockey shoot-out stuck at
   `PT` (now shown as SO); TheSportsDB NBA and NHL games after 00:00Z carry the right UTC
   date. The twenty-second pass (18 October 06:30) showed a
   college football quarter break with the clock already reset (`15:00 - 1st` after "End
   of 1st quarter.", now End of 1st), NHL first intervals on both sources and finished
   Premier League games with straight reds and a second yellow; still no MLB `eventsday`.
   The twenty-third pass (18 October 07:10, the first scheduled capture) showed an NHL
   penalty text without the infraction (`Liam Ohgren Minor`, now named from the type's
   `abbreviation`), a UFC card at walkouts, live college hockey and MLS, and the first MLB
   `eventsday`: Game 4 of the ALCS (00:00Z) is listed under the UTC date with
   `dateEventLocal` the US date; the livescore merge uses that start, so the day-early
   timestamps in livescore and `lookuptv` do not reach the app. The twenty-fourth pass (18
   October 10:28) showed `isRedZone` true during a college football timeout with `down` 0
   and no down text (the red-zone flag now needs a down of 1 or more), Game 4 live in the MLB
   `eventsday` (`IN1`, right start) but missing from the livescore, two finished UFC bouts
   with a winner but no method yet, and college hockey intervals and an overtime final.
   The twenty-fifth pass (18 October 11:27) showed college football half-times that still
   carry the last down (`3rd & 11 at HOU 28`; now left out at half-time), a third UFC finish
   without a method an hour on, and the TheSportsDB `eventsday` MLB row still `IN1` 0–0 in
   the 5th inning (refreshed only later and behind: `IN7` 1–2 at 12:28 against ESPN's 2–5;
   ESPN is the better MLB source). No UFC round in progress in
   either extra run. The twenty-sixth pass (18 October 12:33 to 12:43) sampled the UFC main
   event: `STATUS_IN_PROGRESS_2` with `R1, 3:03` counting down, `R1, -` at the bell,
   `STATUS_END_OF_ROUND` (`End R1`) in the break, a stoppage shown as `End R2` for two
   minutes, then `Final` with the time left (`2:28`) corrected a minute later to the time
   elapsed (`2:32`). A live bout now shows its clock on the score bug. The twenty-seventh
   pass (18 October 13:17) showed a college double-overtime final (`Final/2OT`, read
   correctly) and the finished card; three UFC finishes never got a method within four hours.
   The twenty-eighth pass (18 October 22:40) showed the first live cricket: a Pakistan–Sri
   Lanka ODI chase and a South Africa–Australia Test, both read correctly; ESPN's cricket
   summary has no ball-by-ball plays. International cricket has no league in the app (only
   the Big Bash through TheSportsDB); offering it is a user decision.
   Still not seen in real data (see the latest pass in `IPTV-SPORTS-DATA-FIELDS.md` for
   the full list): the NFL two-minute warning on the scoreboard; NBA overtime in progress; an NHL power
   play, empty net or shootout on the scoreboard, and a shootout in the summary; a pitching
   change as the MLB scoreboard's last play; TheSportsDB baseball half-innings, extra
   innings; a TheSportsDB
   penalty shoot-out after extra time; tennis point score and the
   server during a match tie-break; NASCAR stages;
   AFL/NRL clocks (off season); football own goals, penalties, second yellows and extra
   time. ESPN sends no gaps, pit stops or fastest lap during an F1 race. Coverage ideas from
   the 12 October league lists (not added; user decision): South American and Nordic
   football on ESPN that the parser already reads (chi.1, col.1, uru.1, per.1, ecu.1,
   par.1, ven.1, bol.1, Copa Sudamericana, bra.2, Copa do Brasil, arg.2, swe.1, nor.1,
   den.1, sco.2, eng.5, ned.2, caf.champions, rsa.1, ind.1); Australian leagues only on
   TheSportsDB (Supercars 4489, Super Netball 4540, WNBL 5113, AFLW 5311, Sheffield Shield
   5530, Australia Cup 5180, State of Origin 5835, ABL 5107, United Cup 5872; netball and
   motorsport shapes need checking first); no parser yet for lacrosse, college
   volleyball, water polo, field hockey or FIBA.
3. Follow-ups (small, not started):
   - Hard-coded "v"/"@" are still built in core text (`IptvSportTimeline.kt`,
     `core/iptv/SportsFixtures.kt`, `SportsGuideCells.kt`, `SportsOverlayText.kt`, the
     fixture title in `SportsFixturesParsers.kt`); the screens use
     `iptv_ui25_sep_versus`/`iptv_ui25_sep_at`. A custom league can only be removed by
     unticking it and leaving the picker.
   - Sport recordings are scheduled with an overtime allowance and stopped early once the
     game is final only while the app runs (`IptvSportsNuvio.maintain`); in the background
     they run to the full allowance. Older pending sport recordings without stored team
     names re-key after a league move only when one game fits. Golf is assumed to finish
     about 18:00 Eastern on its final day (`SportsFixtureTimes`). `array.getString(i)` on
     app-written JSON in `core/iptv/CatalogueReview.kt` and `SetupMerge.kt` would read
     "null" on Android if an entry were ever null.
   - Tennis results from earlier days are dropped by the parser's 12-hour rule.
   - NBA team fouls and timeouts are not stored.
   - Recordings in the Movies folder store the MediaStore id rather than the file name.
   - Men's college basketball needs a halves timeline in Game Centre (UI work).
   - Ordinal table positions ("1st") are English only; other languages show the number.
   - Baseball inning words built in core ("Inning 4" from TheSportsDB, "Mid 4"/"End 4" on the
     score bug) are English only.
   - TheSportsDB MLB postseason rows starting after 00:00Z carry the US date with the UTC
     time in livescore and `lookuptv`, so a live row looks a day old and is skipped as stale;
     NBA and NHL rows are dated correctly (17 October captures). The MLB `eventsday` (18
     October 07:10) dates the same kind of game correctly (`strTimestamp` UTC,
     `dateEventLocal` the US date), so `SportsDbEvents.start` needs no change, and
     `SportsDbLive.merge` applies a livescore row to the `eventsday` fixture's own start (the
     row's timestamp is not read), so a live MLB row is not skipped as stale. Closed unless a
     live capture shows otherwise. TheSportsDB `AOT`/`AP` finals show only "Final" (`AP` also
     means after penalties in football); a stuck `PT` row settles 30 minutes after the game
     length, without the shoot-out winner. The capture script asks `eventsday` for the PC's date, which on a
     Brisbane morning is the next US day, so it now also asks for the previous day
     (`v1-eventsday-mlb-prev`). "Serving for the set" also shows when it is for the match.
   - Not offered: ~200 minor football competitions, other golf tours, other MMA
     promotions, FIBA and minor sports. Pre-game NHL/MLB team stats are season figures.
4. Waiting on the user (do not do these without them; give a recommendation and wait):
   - Channel-matching ideas for Sport (from the 11 October comparison): event and team
     channel names, confidence labels on matches, remembering the feed the viewer chose,
     grouping HD/4K versions of a channel, a "My countries" filter, more cricket.
   - QR sign-in, TMDB and Trakt in CI builds: the fork needs the repository secret
     `LOCAL_PROPERTIES_BASE64` (base64 of a local.properties with NUVIO_SUPABASE_URL,
     NUVIO_SUPABASE_ANON_KEY and the TMDB/Trakt keys; the plain file contents also work,
     `sdk.dir` is dropped, only key names are logged).
   - TheSportsDB relay: not decided. Terms (17 September 2026) allow paid use in apps
     within the rate limit, require crediting the source, forbid public use of artwork
     without a confirmed licence and reselling. Recommendation: no key in the app; a small
     caching relay is permitted. Today each viewer enters their own key (sealed on the box).
   - HTTP/3: the user is doing it separately on a `feature/http3` branch (Cronet), not here.
5. After a clean device pass: ship in the full app — enable `FEATURE_IPTV_ENABLED` in
   `full`; move these entries of `src/iptvPrototype/AndroidManifest.xml` into the main
   manifest: the permissions FOREGROUND_SERVICE_DATA_SYNC, RECEIVE_BOOT_COMPLETED,
   WAKE_LOCK, SCHEDULE_EXACT_ALARM (maxSdkVersion 32) and USE_EXACT_ALARM, the service
   `IptvRecordingService` and the receivers `IptvRecordingAlarmReceiver` and
   `IptvRecordingBootReceiver`. Do not move `IptvPrototypeActivity` or the label overrides
   (application and launcher icons), which only exist to make the prototype a separate app.
   If the app is published on Google Play, check its policy on USE_EXACT_ALARM first. Then a
   minified release build and a check of an upgrade from an existing install (guide schema
   6, VOD schema 2, catalogue schema 9, recordings JSON with
   `playedAtMillis`/`fixtureKey`/resume and time index, sports cache with
   `sportDetail`/`source`/`events`).
6. Merge (user decision, 9 October): SQUASH-merge `iptv/player-binding` into `main`, done by
   a separate session; afterwards the user retires `iptv/player-binding`, `iptv/wip` and
   `nuvio-test` (the user deletes branches). Never rewrite history.

### Rules in short (full list in "Rules for this branch")

- Git identity for every commit: user.name `ysosrs123`, user.email
  `ysosrs123@users.noreply.github.com`, `commit.gpgsign false`; no trailers or attributions.
- Push only to `iptv/player-binding`. (One-off tidy-ups of `iptv/wip` and `nuvio-test` were
  allowed on 9 October; ask before touching them again.)
- No pull requests, releases, branch deletion, history rewriting, or GitHub
  comments/issues. GitHub use is limited to pushing commits and starting or reading the
  `PR Full Debug Build` workflow (variant `iptvPrototype`, build_type `debug` or `release`;
  one run at a time, a new run cancels the running one).
- Nothing may look machine-written: no mention of automated tools or assistants in code,
  comments, docs, commits or branch names.
- Never store the user's TheSportsDB key anywhere (repo, docs, fixtures, app). Check every
  capture for it before adding fixtures (see below); none has leaked so far.
- Original work: nothing may look taken from other apps or developers (design, layout,
  wording, code). Functional or factual mentions are fine (user-agent presets, "Open in
  VLC", `#EXTVLCOPT`/`#KODIPROP`, protocol names, ESPN/TheSportsDB); genuine licence
  comments stay. From the other sports app the user showed on 11 October, avoid in
  particular: a gold or tinted wash over whole cards, letter-spaced capital section
  headings, a network strip along the bottom of cards, and any of its wording.
- Screens: most users have 4K TVs, some 1080p. Size layouts from the available width (most
  boxes compose at 960 dp wide, some 4K boxes at 1920 dp) and load images at the pixel size
  they are drawn at; keep memory sane on 2–4 GB boxes.
- Design canvas links the user shares are for reference only; nothing from them goes in
  the repository.
- Keep this handover, the progress log, the checklist and the release notes current in the
  same commits as the changes. Australian English. Say separately what is host-tested,
  CI-built and device-tested.

### How the work is done

- Each device pass or capture: record findings here → fix them in parallel workstreams that
  each own a strict set of files → release notes in the same commits → translate new
  strings into all 40 locales → `PR Full Debug Build` → give the user the run link, a short
  list of what to test and the printable test sheet.
- Brief for each workstream: read this handover's Start here, Rules, Code map and Target
  devices; edit only the files you own and describe needed changes elsewhere (file,
  function, old → new); no commits or state-changing git; dense Kotlin matching the
  surroundings, no explanatory comments or KDoc, no dependency changes, guard APIs above 28;
  the originality and no-machine-wording rules above; Australian English; new strings only
  in a named new `values/iptv_*.xml`, apostrophes escaped, no translations; check callers in
  every source set (main, iptvPrototype, full, test, androidTest); pure logic in `core/iptv`
  with JVM tests; host tests with a private `IPTV_HOST_WORK` folder; Compose and Hilt
  compile only in CI, so read component sources rather than guessing parameters; Android's
  org.json has no `JSONObject.keySet()`; a `var x by …; private set` clashes with a function
  `setX`; network to ESPN/TheSportsDB is blocked from the build environment; never write any
  API key; a short write-up at the end with per-item changes, files, new strings, tests,
  unverified points, changes needed in files not owned, and one release-notes line per item.
  For visual work also give the screen rules above and the 11 October originality list.
- Committing while other workstreams are still in progress: keep a temporary worktree
  outside the repository (`git worktree add --detach <wt>` once). Each time, run from the
  main tree `git -C <wt> reset -q --hard && git -C <wt> clean -fdq && git -C <wt> checkout
  -q --detach $(git rev-parse HEAD)` (the main tree's commit, not the worktree's own HEAD,
  which may be stale), copy in only the finished workstream's files, run the host tests
  there, commit in the worktree, push `HEAD:iptv/player-binding`, then `git reset -q <new
  commit>` in the main tree (restore any doc edited only in the worktree with `git checkout
  -- <file>`). Remove it at the end with `git worktree remove --force <wt>`.

### How to build and check

- Host tests: `IPTV_HOST_WORK=<folder> python3 tools/iptv-host-tests/run.py` (core, data,
  androidTest compile). Compose/Hilt UI compiles only in CI.
- Translations: `python3 tools/iptv-host-tests/check_translations.py` must print
  `problems 0`. Plural quantity sets per locale follow `iptv_guide_days_past` in
  `values-XX/iptv_guide_import_strings.xml` (e.g. ar zero/one/two/few/many/other;
  es/fr/it/pt one/many/other; ru/uk/pl/cs/sk/lt one/few/many/other; sl one/two/few/other;
  hr/bs/sr one/few/other; iw one/two/many/other; ro one/few/other; ja/zh/vi/in other).
- Timing-dependent test failures: `tools/iptv-host-tests/repeat_test.py <class> <runs>`
  after a host-test run; run several copies at once to add load. Fix the race in the test
  (two were fixed on 11 October: a latch in `IptvCaptureHttpTest`, a longer stream in
  `IptvRecordingCopierTest`); never skip or loosen a test to get green.
- CI: `PR Full Debug Build` on `iptv/player-binding`, inputs variant `iptvPrototype`,
  build_type `debug` (or `release` for the R8 check); about 13–15 minutes. On failure read
  the job log (the workflow prints a filtered error summary).
- Printable test sheet: `python3 tools/iptv-host-tests/test_sheet.py
  validation/IPTV-DEVICE-TEST-CHECKLIST.md <out.pdf> --build <commit> --run <run id>
  --date "<date>" --sections 7m,7l,…,7f` (sections not yet passed, newest first;
  A4 with Pass/Fail/N/T boxes and notes lines).
  Needs reportlab and the DejaVu fonts (`pip install reportlab`; fonts from the
  fonts-dejavu package, or pass `--font-dir`). Add a new checklist section (the next
  letter after 7m) for each build's new items and update the checklist header to the build.

### Processing a sports capture

- The user runs the capture scripts in `IPTV-SPORTS-DATA-SCRIPTS.md` and uploads zips (ESPN
  scoreboards and summaries, TheSportsDB v1/v2 livescore and lookuptv). Unpack into a new
  empty folder. Before using any file, search them all for anything key-like (TheSportsDB v1
  puts the key in the URL path `/api/v1/json/<key>/`, v2 sends an `X-API-KEY` header; also
  `apikey`, `api_key`, `token=`); never copy the user's key anywhere.
- Compare each sport's live and finished states with the parsers
  (`core/iptv/SportsFixturesParsers.kt`, `SportsSummary.kt`, `SportsDetail.kt`,
  `SportsDbExtras.kt`), add trimmed real fixtures named `*-real-<yyyymmdd>.json` under
  `app/src/test/resources/sports` with tests (`SportsRealData*Test.kt`), and add a dated
  pass to `IPTV-SPORTS-DATA-FIELDS.md`. UI changes that follow go to the Sport files.
- Known ESPN behaviour: no summary endpoint for tennis, golf, motorsport or MMA (the app
  no longer asks; 400/404/410 stop the watch for that game); NASCAR 502s are transient;
  `form` is newest first (reversed when parsed); the F1 event status says FINAL while
  sessions are still live, so the parser follows the sessions.
- Automatic captures (user decision, 18 October): scheduled runs in the cloud environment
  capture ESPN and TheSportsDB with `tools/iptv-host-tests/sports_capture.py`, process the
  capture as below and push fixes. The user relaxed the key rule for this only: the key lives
  in the environment as `THESPORTSDB_KEY` (set by the user); it must still never be written to
  the repository, docs, fixtures, logs or chat, and every saved file is scrubbed.
- The capture script (revised 18 October, user request: capture everything) reads every
  league the app offers from `SportsFixtures.kt`, adds every other league ESPN lists for 17
  sports (`/leagues/dropdown`, about 350 leagues in all; `sports.core.api.espn.com` is not
  reachable from the cloud environment) and up to eight live or finished cricket matches from
  ESPN's cricket score panel. Summaries: up to three live games per league, otherwise the
  latest game finished in the last 12 hours (the app's own leagues also keep a pre-game
  summary); none for tennis, golf, racing or MMA. TheSportsDB: livescore for every sport, the
  `eventsday` lists for the Premier League, AFL, MLB (today and the day before) and the Big
  Bash, and `lookuptv`. One run takes about six minutes and 40 MB.
- Batches (user, 18 October): captures commit locally and push once a day at the first run
  after 18:00 Brisbane, only when the batch changes what the app does (a fixtures, tests and
  docs batch waits for one that does, at most three days); one checklist section, release
  notes block and progress row per pushed batch. Builds are started from the main session.
- Capture plan (18 October; each run removes what the data confirms or what cannot be seen
  before 30 November, then drops the slots nothing needs; scheduled tasks are disabled, never
  deleted). Times are Brisbane; US slots move an hour later from 1 November and European ones
  from 25 October (reminders do this).
  - NBA overtime in progress and its summary; an NBA free throw with the player's name; an
    NHL shootout in progress (scoreboard and summary); a pitching change as the MLB last play
    (World Series, to about 1 November); TheSportsDB baseball half-innings or extra innings
    and a refreshed shoot-out row; a UFC method posted later; college volleyball (no parser
    yet): daily 09:27, 11:27, 13:27 (`IPTV capture – daily US evening`).
  - The NFL two-minute warning on the scoreboard; a quarter break in a live summary; NASCAR
    stages (season to early November): Monday 04:27, 06:27, 11:27 (`Mon NFL`), Friday 11:27
    and Sunday college football (daily US evening, weekend mornings).
  - Football extra time and penalty shoot-outs on ESPN, and a TheSportsDB shoot-out after
    extra time (EFL Cup, DFB-Pokal, Copa del Rey, Coppa Italia midweek rounds): Wednesday to
    Friday 05:27 and 06:57 (`Wed-Fri European cups`, `… late`).
  - Golf after a cut or in a playoff: Saturday and Sunday 03:27 and 07:27 (`weekend
    mornings`), Monday 04:27 and 06:27.
  - Tennis point score and the server in a tie-break (European indoor events): daily 21:27
    and 23:27 (`nightly Europe`).
  - Dropped: F1 gaps, pit stops and fastest lap (ESPN does not send them during a race);
    AFL and NRL live clocks (off season until after 30 November).
  - Disabled on 18 October (covered by the slots above): `Tue-Sun NHL/NBA/MLB`, `Sun
    EPL/MLS`, `weekday tennis`.
- Capture schedule given to the user (Brisbane, UTC+10; European games one hour later from
  25 October, US games one hour later from 1 November): best Monday 04:00–08:00 (NFL);
  Monday ~10:30–13:00 (NFL Sunday night, NHL); Sunday 05:00–13:00 (college football, NHL,
  MLB playoffs, UFC main card ~11:00–13:00); Tue–Sat 09:30–12:30 (NHL, NBA from ~21
  October, World Series late October); Saturday 21:30–Sunday 02:30 and Sunday 23:00–Monday
  02:00 (Premier League); Wednesday and Thursday 03:00–07:00 (Champions League); Fri–Sun
  18:00–21:00 (A-League from late October, NBL); weekday afternoons (Asian tennis). Two or
  three captures 15–30 minutes apart catch half-time, period ends, overtime and shootouts.
  AFL and NRL are in the off-season.

## Earlier session notes (8–11 October 2026; history, superseded by Start here)

The dated updates below were the running notes of the 8–11 October session, newest first.
Where they disagree with "Start here", "Start here" is current.

Update 11 October (late): fifth capture checked (22:46 Brisbane; first live F1 race:
lap and top three now shown; football HT; TheSportsDB status-less/OT rows; `d4fc94d24`).
Build: CI debug green at `d4fc94d24` (run 38142066345); checklist 7l now has 10 items.
ESPN gives no gaps, pit stops, retirements or fastest lap during an F1 race.

Update 11 October (evening): fourth capture of the day checked (first live tennis; UFC
results, cricket results, Game Centre awards, shootout labels; `56de654bc`), and a timing
race in `IptvRecordingCopierTest` fixed. Build: CI debug green at `fcd9f1f0b` (run
38128849657); checklist starts at 7l; printable sheet regenerated. The user has a capture
schedule (best: Monday 04:00–08:00 Brisbane for NFL). Still unchecked with real data: NFL
live, live Premier League added time, NHL empty net/shootout in progress, MLB pitching
change, golf mid-round, F1 race in progress, live cricket.

Update 11 October (afternoon): the Sport visual decisions (items 1–9 in "Decisions — 11
October 2026 (Sport visuals)") are done in code (`3397061c8`, `7bf9f8b5a`, `428ec7c99`).
Build: CI debug green at `428ec7c99` (run 38109063375); checklist starts at 7k; a printable
test sheet was generated from the checklist (7k–7e). Follow-ups: move the multiview
"Channel and programme" key (`multiview-labels`) into IptvLivePreferences and the setup
backup; unused strings `iptv_sport5_section_other_feeds`, `iptv_sport7_source_both`,
`iptv_sport7_source_needs_key`; past tennis results on earlier days are dropped by the
parser's 12 h rule; ordinal table positions are English-only (other languages show the
number). Channel-matching ideas (event/team channel names, confidence labels, remembered
feed, HD/4K grouping, "My countries", more cricket) still await the user's choice.

Update 11 October (midday): second capture of the day checked (first real live baseball;
`1cafb6dab`): baseball between innings and at-bat last plays, NHL offsetting penalties,
college football at midfield, worldwide TV channels, 14 more football competitions. Build:
CI debug green at `1cafb6dab` (run 38100231762). Checklist: start at 7j, then 7i, 7h.
The third capture (11:03, ESPN only) is checked too
(`7741e4029`, run 38101462318, the build to use). Men's college basketball still needs
a halves timeline in Game Centre (UI work), not done.

Update 11 October (evening): findings from the NAS recording test fixed in code. Recordings
play as an HLS byte-range playlist built from a time index saved while recording (older
recordings are indexed once on first open), with resume kept in the recording store and
no watch party; the guide reaches earlier programmes for catch-up. A timing race in
`IptvCaptureHttpTest` was fixed. Build for the device pass: CI debug green at `4d0c61b39`
(run 38095469187). Host tests: core 881, data 293. Checklist: start at 7i, then 7h, 7g.

Update 11 October (morning): new ESPN/TheSportsDB capture checked (fixes and 34 more
leagues, `1ad071a96`), full-share recording messages and a 2 GB backlog cap (`b8937dbff`),
league list grouped by sport with built-in merging of searched leagues (`f79646c53`). Build
for the device pass: CI debug green at `f79646c53` (run 38081394566). Host tests: core 859,
data 288. Checklist: start at 7h, then 7g, 7f, 7e. Follow-ups: men's college basketball
needs a halves timeline; ~200 minor football competitions, other golf tours, other MMA
promotions, FIBA and minor sports are not offered; pre-game team stats are season figures.

Update night of 10 October (user asleep): all night-review findings fixed and a second
review of those fixes done (three more recording fixes); Game Centre formation and card
marks; TheSportsDB checked against the user's real captures. Build for the next device
pass: CI debug green at `c1685393a` (run 38057188837); release (R8) green at the same
commit (run 38058607115).
Host tests: core 848, data 285. Checklist: start at 7g, then 7f and 7e.

State (end of the 8–9 October session):
- Everything is committed and pushed. Latest green CI: debug `8354d89` (run 37867719176),
  which contains every fix up to the 9 October device findings plus widgets, sport
  follow-ups and translations; later commits are docs only. Minified release (R8) green
  at `03808ae` (run 38014569747, 10 October) — run it again before shipping.
- Host tests: core 748, data 276, androidTest compiles
  (`python3 tools/iptv-host-tests/run.py`).
- Device testing: user passes on 8 October (`f29cbe2`, `24d6868`), 9 October (`374e1f6`,
  findings below, all fixed in code) and 9 October second pass on `8354d89` (26 findings,
  recorded below, all fixed in code on 10 October).

Update 10 October: the 26 findings of the second 9 October pass (`8354d89`) are fixed in
code (`f002c10`..head, see that section and the release notes); the checklist has a new
section 7c. Host tests: core 774, data 276. CI: see "Validation" in the release notes.
Unverified on a device (from the helpers' reports): SurfaceView corner picture (HDR mode
switch, square corners, panel fades no longer dim the video), AAC target loudness and the
+4 dB surround lift, seamless handover into multiview (surface switch; reverse direction
not done), audio-renderer toggling per tile (possible short rebuffer), decoder budget now
probing 4K/high frame rates (`multiviewDecodeBudget`), held Up/Down handled by the guide
itself, eight widget layouts' text fit, phone page flow in a real browser, recordings via a
local 127.0.0.1 stream (`IptvRecordingStreamServer`) in Nuvio's PlayerScreen, IPTV titles
on Nuvio's detail page (`iptv-vod:` ids handled by `IptvVodMeta` in MetaRepositoryImpl;
series open after episodes and TMDB load, up to ~45 s worst case; Play not Resume for
provider-only titles; library/watched for `iptv-vod:` ids excluded from sync), guide reads
outside write transactions during imports, per-account refresh locks (max 2 sources).
Follow-ups not done: show NHL goalies (`SportsSummary.goalies`) and F1 `RaceSession.top`
in the UI; Sport redesign (item 27) built in `d81ba81`, Home rows / Games now in `3de7568`, the rest
after it; the video score bug (IptvSportsOverlay `BugSegment`) keeps its own look.
Done 10 October (user away): those remaining screens moved to the new look (Game Centre,
team page, player pop-up, All scores, multiview score tiles, widgets; the full-screen banner
follows via `GameScoreLine`), NHL goalies (`SportsGoalies` in SportsLedger.kt) and racing
session winners shown. CI green at `3de7568` (run 38012700521) and at `3065341` (run
38013663627, the build given to the user for the next device pass). Unverified: row heights computed from text sizes, live hockey now loads the
focused game's summary (one extra request).

Next, in order:
1. Device pass on `d4fc94d24` (run 38142066345; checklist 7l, 7k, 7j, 7i, 7h, 7g, 7f, then 7e); take the findings,
   record and fix them the same way. Playback options, menu order, external player and
   source expiry (7e) have not been tried on a device yet.
2. Waiting on the user (do not do these without them):
   - QR sign-in in CI builds: the fork needs the repository secret `LOCAL_PROPERTIES_BASE64`
     (base64 of a local.properties with NUVIO_SUPABASE_URL, NUVIO_SUPABASE_ANON_KEY and the
     other keys: TMDB, Trakt, …). Without it CI APKs have no Nuvio sign-in, TMDB or Trakt.
     The debug build step also accepts the plain file contents, drops `sdk.dir` and logs the
     key names it found (never the values).
   - Weekend ESPN capture during live play (`IPTV-SPORTS-DATA-SCRIPTS.md`); then check the
     live-only fields (clocks, NFL situation, MLB count/bases, tennis serve, golf mid-round,
     F1 results, UFC rounds) and replace synthetic fixtures with trimmed real ones (as done
     for the 8 October evening capture in `374e1f6`).
   - TheSportsDB relay: not decided. Terms (17 Sept 2026, pasted by the user) allow paid
     use in apps and services within the rate limit, require crediting the data source,
     forbid public use of artwork without a confirmed licence and reselling. Recommendation:
     no key in the app; a small caching relay is permitted; optional email to TheSportsDB
     to confirm. Today each viewer can enter their own key (sealed on the device).
   - HTTP/3: the user is doing it separately in a local session on a `feature/http3` branch
     (Cronet), not on this branch. The stats HUD only shows whether a server advertises h3.
3. After a clean device pass: ship in the full app — enable `FEATURE_IPTV_ENABLED` in
   `full`; move `src/iptvPrototype/AndroidManifest.xml` additions (WAKE_LOCK, recording
   service, receivers) into main; minified release build; upgrade-from-existing-install
   check (guide schema 6, VOD schema 2, catalogue schema 9, recordings JSON with
   `playedAtMillis`/`fixtureKey`, sports cache with `sportDetail`/`source`/`events`).
4. Merge (user decision, 9 October): SQUASH-merge `iptv/player-binding` into `main` so
   `main` gets one commit with the final, clean files; afterwards the user retires
   `iptv/player-binding`, `iptv/wip` and `nuvio-test` (the user deletes branches). Never
   rewrite history. The merge is done in a separate session.

Known gaps and things to verify on a device (latest):
- Channel switching (`43a1d62`): close starts on OK, late responses are closed, 8 s wait
  with loading shown, repeat OK opens full screen. The decoder is still released and
  recreated per switch (player reuse not done). OkHttp cancel behaviour was checked on the
  host against 5.x; the app ships 4.12. Log lines: `live close started`, `live close ms=…
  dns= connect= connected= cut=`, `live close unconfirmed reason=`, `tune repeat ignored`.
- Back/Left (`495a527`): full screen → guide → Live TV menu → "Leave Live TV?"; full-screen
  Left twice = categories; Left on Sources = guide; Left on Movies/Series = Live TV menu.
- Widgets (`930df01`, `8354d89`): Up from the guide reaches them via default focus search
  (unverified); text fit on 140–240 dp tiles estimated; city names in code (English).
- Opaque panels: Live TV settings dialogs now get the 0.9 floor; `NuvioDialog` classic
  branch uses `maxOf(0.85, LocalGlassBodyFloor)` (only changes dialogs inside Live TV).
- IPTV movies/series (`044f6ad`): Nuvio's detail page only when an installed addon returns
  metadata within 8 s; otherwise the IPTV title page (`core/iptv/VodDetailRoute.kt`).
- Sport: combined sources (`64ae717`) — per-league ESPN else TheSportsDB with the user's key,
  fallback after repeated ESPN failures, v2 livescore, lookuptv for followed teams, league
  search, Team logos (ESPN only by default), credit line. "Find channels using" (guide /
  broadcasters / both), matching across Guide days + 1 (max 8), Record and "Record when a
  channel is found" on match cards, pending rules checked app-wide every minute while
  Nuvio is open (`ea3fc4e`, `befcd5f`). Favourites match loosely across name variants.
  TheSportsDB v2 livescore, lookuptv and league-list shapes are from documentation only.
- Stat labels added in `SportsSummary` (e.g. "Run metres") are English in core; NHL/MLB
  pre-game team stats are season totals shown like match stats.
- Recording names (`ea3fc4e`): `RecordingText.plain/display` maps decorative letters to
  ASCII and drops symbols; stored names are cleaned on load too.

Sports build summary (8 October; details in the release notes and design pass 2):
all four parts are built — shared live service and settings (`core/iptv/SportsLive.kt`,
`data/iptv/IptvSportsLive.kt`), game summaries (`SportsSummary.kt`,
`IptvSportsSummaryClient.kt`), per-sport detail (`SportsDetail.kt`, field
`SportsFixture.sportDetail`; `detail` is the status text), guide cells and Games now lane
(`SportsGuideCells.kt`, `IptvSportsGuide.kt`), overlays (`IptvSportsOverlay.kt`), Game Centre,
feeds, markers, multiview data tiles (`IptvGameCentre.kt`, `IptvSportFeeds.kt`), Sport section
(`IptvSportCards.kt`, `IptvSportHero.kt`, `IptvSportTimeline.kt`, `IptvAllScores.kt`), Nuvio-wide
(`IptvSportsNuvio.kt` hooked in MainActivity and PlayerScreen, `IptvHomeRows.kt`,
`IptvTeamScreen.kt` route in NuvioNavHost, `SportsRecordRules.kt`), combined sources
(`SportsSources.kt`, `SportsDbExtras.kt`, `IptvSportsDbExtras.kt`), widgets (`LiveWidgets.kt`,
`IptvWidgets.kt`). Two independent reviews of the sports code were done and every finding
fixed (`f36382e`); the earlier review of `d263b18..HEAD` is done too (`29b217a`).

Caveats and behaviour changes to verify (from the helpers' reports, 8 October; still relevant unless marked):
- Guide: over-budget guides drop the last-parsed (whole) channels (`budget=` in the log);
  programmes with the same channel and start collapse to one; name matching is now a
  fallback for every channel; a missing manual target falls back to automatic matching;
  old catalogue rows without guide keys import unfiltered until the source refreshes.
- Playback: shared OkHttp client with `retryOnConnectionFailure` on (check strict
  connection-limit providers); pre-warm opens DNS+TCP only (no TLS, no request); the
  alternating close failure was attributed to a main-thread TLS close (likely, not
  proven) — the close log lines will show the real reason; corner/inset pictures show the
  logo for HDR or >1080p for the rest of that player's life.
- Behind-live: TS counts only delay added after the start; the HLS start-late seek was
  removed; Right opens the controls on Return to live only when behind live.
- Recording location picker: a default pointing at a removed network share falls back to
  This device; choosing an unavailable location is refused at record time.
- Styling: glass panels in Live TV get a 0.9 minimum opacity (`GLASS_FLOOR` in
  `IptvTheme.kt`); importing an old setup bundle without the "solid" key still sets Solid
  panels on (`core/iptv/SetupBundle.kt`).
- Sport (updated 9 October): fixtures come from the app-wide `IptvSportsLive` service (all
  chosen leagues while Live TV is open; followed teams only in the background while Nuvio is
  open); the featured hero shows the first fixture until a card is focused; favourites are
  stored as league + team name and match loosely across name variants.
- VOD: m3u8 movies untested; mpv busy/refused messages depend on mpv logging the status;
  resume for unmatched titles stays local.
- Navigation (superseded 9 October): Back from the guide now opens the Live TV menu and then
  asks "Leave Live TV?"; the Modern Home layout appends Live TV rows after the catalogue rows.
- The user's earlier "pre-fetch the stream on focus" request was implemented as a
  connection pre-warm only; fetching media in advance would use a provider connection.

How work was done (keep doing it this way):
- Parallel helpers with strict file ownership, briefs naming the files they own, their
  own `IPTV_HOST_WORK` folder, and new strings only in a named new `values/iptv_*.xml`.
  Shared files touched by two helpers are committed together once both finish (a
  temporary `git worktree` was used to commit one helper's files when others' work in
  progress did not compile).
- Translations: after each feature, translate new strings into all 40 locales in two
  batches of 20; check with `python3 tools/iptv-host-tests/check_translations.py` (prints
  `problems 0`); also check no duplicate names per `values-*` folder.
- CI lessons: Android's org.json has no `JSONObject.keySet()`; unescaped apostrophes break
  aapt; check other source sets (`app/src/iptvPrototype`, `app/src/full`, androidTest)
  when changing a signature; one CI run at a time (starting one cancels the running one);
  the release build needs `includeComposeMappingFile = false` (smbj's bcprov has Java 25
  classes).
- After each device pass: record findings and status in this file and the release notes,
  then build.
- Helper brief essentials (give every helper): read the handover's rules and code map and
  the relevant sports docs; edit only owned files and report needed changes elsewhere; no
  commits or state-changing git (the coordinator commits); match the surrounding style (dense
  Kotlin, no explanatory comments or KDoc); no mention of automated tools anywhere;
  Australian English; strings only in the named new values file with apostrophes escaped;
  check all callers in every source set; pure logic in core/iptv with JVM tests; host tests
  with a private copy of build/iptv-host-tests (`IPTV_HOST_WORK=<folder>`); Compose and
  Hilt compile only in CI, so read component sources instead of guessing parameters; network
  to ESPN/TheSportsDB is blocked from the cloud environment, so use documented shapes and
  synthetic fixtures named `*-synthetic.json` (real ones `*-real-<date>.json`); never write
  the user's TheSportsDB key anywhere; final report under 300 words with files, tests,
  unverified points and changes needed in files the helper does not own.
- Coordinator checks before each commit: run host tests on the full tree, or on the last
  commit plus only the finished helper's files in a temporary worktree when others are
  still working; update the release notes in the same commit as any behaviour change;
  translate new strings; run the `PR Full Debug Build` workflow (variant iptvPrototype) and
  record green runs in the release notes "Validation" and here.
- Plural strings are referenced as `R.plurals.*`: when looking for unused strings, search
  `R.string.` and `R.plurals.` (a clean-up that missed plurals broke the build once).
- Kotlin: a `var x by …; private set` property clashes with a function named `setX` (JVM
  signature clash); name such functions differently.

## Status

- Live TV covers M3U/Xtream/Stalker sources, guides, the guide-first screen, full screen
  with the player's control deck and stats HUD, catch-up, search, multiview, Sport (guide
  and optional fixtures), recording (internal, USB, SMB, WebDAV, FTP, Movies folder),
  phone/PC setup and transfer, IPTV movies and series, experimental local timeshift and
  the streaming-stability settings. The user has tested on the AM9 and other boxes
  regularly since 7 October (see the "Device findings" sections); the newest work is
  listed as untested in "Start here".
- Live TV is reached from Nuvio's sidebar and its settings open inside Live TV (since
  10 October), only in the `iptvPrototype` flavour (`FEATURE_IPTV_ENABLED`); the `full`
  flavour hides them. Decision (user, 8 October 2026): IPTV ships as part of the full app
  (steps in "Start here", Next item 5).
- The original capture chain stays disabled; local timeshift uses a separate ring file.

## CI build — 6 October 2026

- GitHub Actions run 37419887574 (`PR Full Debug Build`, variant `iptvPrototype`,
  commit def2b19) is green: full app compile including the Compose/Hilt UI changes,
  IPTV core and data-layer unit tests under Gradle, and the APK. This is the first
  Gradle build since `b68985a`. Not device-tested yet.
- `LocalTsSegmentExtractorTest` skips under Gradle (stubbed `android.util.SparseArray`);
  `LocalTsSegmentExtractorAndroidTest` covers it on device.
- The app builds per-ABI APKs only (`isUniversalApk = false`); the workflow uploads
  `app-<variant>-arm64-v8a-debug.apk`.
- The prototype installs as `com.nuvio.iptv.prototype` ("Nuvio IPTV Prototype")
  alongside the normal app; debug builds add no suffix. CI signs with a throwaway key, so a prototype signed with
  another key must be uninstalled first (this clears its sources).
- Logs: `get_job_logs` with `return_content` shows the last 8 KB; the workflow prints
  a filtered error summary on failure. Without GitHub tools, run status is readable
  from the public REST API, but logs and re-runs need the user.

## Current work plan (7 October 2026; history)

Everything below is host-tested and (unless marked) committed; none of it is
device-tested. New strings are translated into all 40 locales unless marked.

Done — wave 1 (`277489d` – `d263b18`, CI green):
- Storage: recording location internal / USB (FAT32 split into parts, exFAT, NTFS,
  read-only detection) / SMB 2-3 via smbj (user approved the dependency) with local
  spool and resumable upload; safe file names.
- Live TV screen: catch-up scrubbing across programmes, auto return to live, guide
  densities, sticky titles, programme artwork, channel and category reorder, all
  sources together, multiview across sources and more layouts, picture-in-picture.
- Playback: per-channel headers, endless retry, frozen-video detection, format probe,
  faster TS zap, Xtream catch-up address styles, display frame-rate handoff.
- Setup: phone guide assignment and profiles, device-to-device copy, encrypted
  backup/restore, account groups screen, Stalker grouping, refresh review.

Done — wave 2 and later (`7b26803` – `0c7ebe8`):
- Phone setup page: recordings list, download and "Open in VLC" (HMAC-signed links
  valid 6 h, single-range HTTP, multi-part USB recordings joined, network-share
  recordings streamed; at most 3 downloads; the setup screen must stay open).
- WebDAV (OkHttp; SabreDAV partial-update append, else one PUT after the recording
  ends) and FTP/FTPS (own client, APPE append, explicit TLS) recording targets;
  "Trust this certificate" pinning for self-signed NAS certificates. Non-append WebDAV
  keeps the whole recording on the box until it ends (spool free space is checked only
  at start).
- Sports fixtures: Off (default) / ESPN (unofficial) / TheSportsDB (user's own key,
  sealed); league choice; fixtures matched to channels by guide titles and broadcaster
  names; "Fixtures" row in the Sport view. ESPN and TheSportsDB could not be reached from
  the build environment; league paths and names need a device check. Opta/Stats
  Perform, Sportradar, Flashscore, Sofascore and similar: contract-only or no public API.
- Experimental local timeshift (off by default): the capture chain was found unsuitable
  (HLS-only, baseline-AVC test streams, no audio, connection hold on failure), so it is
  a capped TS ring file (15/30/60 min or automatic; internal or USB) written by one
  provider connection and read by the normal live player; full-screen single view, TS
  only; falls back to direct playback on any error. Entering full screen costs one
  short reconnect.
- IPTV movies and series: separate VOD database (`iptv-vod.db`); Xtream VOD/series
  lists streamed in the background refresh, series info on demand; M3U movie/series
  entries moved out of the live channel list; title matching (decorations, years,
  episode markers); `iptv-vod:` references, provider URLs built only at play time.
- IPTV movies and series as a stream source on Nuvio's pages (Settings → Playback →
  Stream selection, off by default, per profile); one chip per source; references are
  what the link cache, history and Trakt/Simkl/MDBList see. Known gaps: a playing VOD
  stream is not counted by Live TV's connection count; a busy provider is retried twice
  before the message; "open in external player" shows but fails; automatic failover
  skips IPTV; mpv resolves on the main thread; possible duplicate User-Agent header on
  VOD requests.
- Live TV in Nuvio's navigation: sidebar destination; Left from the guide opens the Live
  TV menu, Left again the Nuvio sidebar; Back closes Live TV layers, then opens the
  sidebar, then exits like Home; Exit item removed; sidebar/top bar hidden in full
  screen and multiview; Live TV settings are a main Settings category (`LIVE_TV`, Watch
  group, `IptvSettingsContent`); the `iptv/settings` route is gone.
- Streaming stability: seamless TS reconnect keeping the buffer (`IptvResilientDataSource`);
  "Start playback after" Fast 1 s / Normal 2.5 s / Safe 5 s; "Safety buffer" Off / 10 /
  20 (default) / 30 / 60 s built at 0.97x on TS (HLS seeks back instead), memory-capped
  (16 MB low-RAM, 48 MB otherwise); HLS offset from the playlist; Wi-Fi high-perf lock
  while playing; 1/4 MiB socket receive buffer; per-source user agent; HUD rows for
  buffer, behind-live, speed, protocol, Alt-Svc h3 and reconnects; fast-forward key goes
  live. Open: no Go live button in the control deck (remotes without fast-forward).
  The user's provider panel answered HTTP/1.1 with no Alt-Svc, so HTTP/3 is not offered.
- Fixes found by CI: Android org.json has no `JSONObject.keySet()`; the prototype
  activity used the removed `onBack`; certificate failures on a later route were
  reported as "unreachable" (real bug, `2bef868`).

Done — committed together with the screens below:
- "Movies folder" recording location (Android 10+): records to the spool, then copies
  into MediaStore `Movies/Nuvio Recordings/` (pending entry, published after the size
  check); list, play, delete and phone download work through the content id. Delete of
  a file the app no longer owns (after a reinstall) shows a message instead of failing.
- Movies and Series in the Live TV menu (`iptv/vod/{kind}`, `iptv/vod-title/{ref}`):
  category rail, poster grid with paging, search, source filter; matched titles open
  Nuvio's detail page (a small in-memory allow list makes the IPTV stream source show
  there even when the global setting is off); unmatched titles get a Live TV page with
  play/resume and episodes, resume stored per VodRef in `iptv-vod.db` (schema 2) and
  never sent to Nuvio history. "Movies and series" settings group: artwork from Provider
  (default) or Nuvio (TMDB lookups for visible posters only, cached 30 days, honours
  TMDB 429), and a switch per source.
- Strings `iptv_media_strings.xml` and `iptv_vod_browse_strings.xml` are translated into all 40 locales.

Done after `e20d157` (host-tested, CI pending):
- VOD gaps closed: a playing IPTV movie/episode takes a `VOD` lease in
  `LiveSessionAdmission` (kept across data-source reopen, released on player release,
  source/engine switch, next reference or failed open), so Live TV and recordings see
  it; busy/refused providers raise a non-retryable `IptvVodPlaybackException` with the
  IPTV message; external player hidden and refused for `iptv-vod`; failover includes
  IPTV through the same resolver; mpv resolves off the main thread and holds the lease;
  one User-Agent per request (provider/source value or the default). "Return to live"
  pill above the Live TV full-screen deck while behind live (cushion, local timeshift,
  catch-up near live, paused live); not added to the shared `PlayerControlAction`.
- Live TV rows on the Nuvio Home screen (IPTV flavour only): favourites (now playing and
  progress), sport on now, recently added movies and series (off by default),
  recordings; a "Home screen" group in Settings → Live TV with a switch per row. Rows
  load 800 ms after Home, cache 3 minutes, and open Live TV through `IptvLiveLaunch.channel`
  (no route argument, so sidebar navigation is unchanged). Classic and Grid insert the
  rows after Continue Watching; Modern appends them after the catalogue rows. Strings
  `iptv_home_strings.xml` translated into all 40 locales.
- Minified release build check (R8) with all new code. Run 37594253652 failed in
  `produceIptvPrototypeReleaseComposeMapping` ("Unsupported class file major version
  69"): smbj 0.15.0 depends on bcprov-jdk18on 1.85.2, a multi-release jar with Java 25
  classes, which the Compose mapping task cannot read. This also affects the `full`
  release build. Fixed in `ec6f16f` with `includeComposeMappingFile = false` (the file
  only improves Compose stack traces in minified builds). Minified release build green
  at `ec6f16f` (run 37596443549). Alternative if the mapping
  file is wanted: pin an older bcprov without `META-INF/versions/25`.

Next (not started; the review helpers stopped at an account usage limit before
reporting, resets 10 October 2026 00:00 UTC):
- Independent review of `d263b18..HEAD` in three areas (storage and setup server;
  playback, streaming and navigation; VOD, sport and Home). Brief kept outside the repo:
  read-only reviewers, concrete failure paths only.
- Device test checklist: [IPTV-DEVICE-TEST-CHECKLIST.md](IPTV-DEVICE-TEST-CHECKLIST.md) (build `f29cbe2`).
- Decisions recorded: tracking for matched titles uses Nuvio's normal history and
  services; unmatched titles resume locally; navigation and settings integration as
  above (reverses the earlier "IPTV settings only on the Live TV side").

## Decisions — 11 October 2026 (Sport visuals)

After comparing with screenshots of another sports IPTV app (for ideas only; its look and wording
must not be copied), the user asked for all of these, in our own design language and working on
both 1080p and 4K TVs (layouts sized from the available width; images loaded at drawn pixel size):
1. Sport page: no stranded single card — fill thin days with earlier results, tomorrow's games and
   "Your teams next".
2. Fix truncations: hero broadcaster line, "5 othe…" feeds button (short "+5 feeds"), card footer.
3. Pre-game hero uses its empty space: form as five W/D/L marks, league position, last meeting.
4. Denser, equal-height game cards, status always in the same place, cards per row from the width.
5. Team colours in the hero (both teams' colours in the top rule or a soft glow behind each crest).
6. Multiview: a slim live score strip on each video tile matched to a game.
7. All scores: a day strip with counts (live, games, no games), including past days with results.
8. League picker as its own screen: sports down the side, leagues as crest tiles with a tick, a
   short "not affiliated" note in our own words.
9. Multiview, all tiles (not only sport): an option to show the channel name and the programme
   now playing along the bottom of each tile; combined with the score strip on matched games.
Not yet decided: the channel-matching ideas (event and team channel names, confidence labels,
remembering the chosen feed, grouping HD/4K versions, "My countries", more cricket).

## Device findings — 11 October 2026 (build `f79646c53`; ~3 h recording to an SMB NAS)

The overnight recording to the NAS worked. Findings (all fixed in code: `b9e16e2e4` guide,
`b8e315583` recordings; checklist 7i):
1. Recording opened in the player showed a runtime of just over 24 hours on the timebar.
   Cause: recordings play in Nuvio's PlayerScreen as a raw .ts file over the local
   `IptvRecordingStreamServer`; ExoPlayer works the length out from the first and last
   PCR, which a reconnect gap or a timestamp wrap in a live recording throws off.
2. Watching part of a recording, backing out and opening it again starts from the
   beginning. Cause: the player route uses `startFromBeginning = true` with no id, so
   nothing saves or restores a position.
3. Long seeks struggle. Cause: TS seeking in ExoPlayer binary-searches the file by PCR
   through many range requests, each one a read from the NAS; with timestamp jumps the
   search can also land in the wrong place.
4. Remove the watch party button for recordings; keep it for IPTV movies and series.
   (Live channels play in the Live TV player, which has no watch party.)
5. Provider catch-up: no way to move back in the guide to earlier programmes. Cause: the
   guide window starts half an hour before now (`IptvLiveViewModel.guideWindow`); OK on a
   past programme of an archive channel already plays catch-up, but the past cannot be
   reached.

## Review — night of 10 October 2026 (`01f19bbed..96f8939ea`)

Two read-only reviews of everything since the last full device pass. All findings below are
fixed (`42fb1a5f9`, `69100afe3`, `86d5bd8b3`, `278e688f6`, `7c7e0e4ae`; checklist 7g) except
the two "not changed" items:
- High: recording names without the id suffix could overwrite files on network shares and
  delete same-named MediaStore entries (uniqueness was only checked on local storage).
- Medium: PIN-locked profile reachable by a paired phone right after app start (PIN map not
  loaded yet); tunnelling checks used the setting, not the actual state; Nuvio audio settings
  read with runBlocking on the main thread; preferred channels outranked the channel that
  actually shows the game; "Always in Sport only" channel picks could be dropped; sport
  matching query count up to 5×; Sport only 24 h limited by a 3,000-record cap.
- Low: phone list saves not atomic; phone change prompt over the profile gate; address
  check on the main thread; preference reads during composition; focus requests treated as
  successful when refused; category picks matched case-sensitively in queries; Stalker
  expiry read as UTC; HEVC tiles measured against the AVC budget.
- Not changed (waiting for a device test): multiview decode budget uses the larger of
  performance points and size/rate support; check the `multiview device` log on the AM9.
- To check on a device: tunnelled playback kept alive while in settings may need a re-tune
  (now re-tunes itself after 10 s if no frame arrives; log `live tunnel picture missing`).
- Second review of the fixes (`44b68ebce..7c7e0e4ae`): no compile or SQL errors; three
  recording issues fixed (share folder listing delayed the start by up to 8 s; retried
  WebDAV uploads left orphan partials; delete during the final rename could leave the file).
- Follow-ups not done: Movies-folder recordings still store the MediaStore id rather than
  the final name (`IptvRecordingStore`); the folder check covers new names. Sport only is
  capped at 200 channels (`browse.sports` allows 1..200).

## Device findings — 10 October 2026, evening (build `e7e1d17b5`, run 38029912007)

Checklist 7e/7d passed except (all fixed in code, `9a88d0d41`..head; checklist 7f):
1. Live TV settings: toggling Pure black moves focus back to "Sources and guides"; focus
   should stay on the toggle.
2. The main guide should show all channels without the Sport only / All channels toggle
   (keep the toggle on the Sport page only).
3. Sport page: hard to reach the Sport only / All channels toggle from the cards above.
4. Left from Recordings returns to the guide but focus is not where it was; returning from
   any other Live TV screen, panel or settings should restore the previous focus.
5. Opening settings (or other Live TV screens) while a channel plays in the corner or full
   screen should keep it playing, so it is still playing on return.
6. Multiview with four pictures: the focused picture is enlarged with a border and overlaps
   the others; it should keep its size with a gentle border.

## Decisions — 10 October 2026 (playback options, menu, sources)

User agreed to these recommendations (in progress):
- Live TV settings, new "Playback and audio" group: AFR follows Nuvio's frame-rate and
  resolution matching by default with an optional Live TV override; Nuvio's passthrough
  settings applied to Live TV (optional override); tunnelled playback option (off by
  default; not with volume boost/surround lift; main player only, not multiview tiles);
  "Prefer surround audio (5.1)" with stereo fallback and "Prefer audio language"; audio
  decoder Automatic / Prefer app decoder (video stays hardware-only).
- Channel hold-OK menu: "Open in external player" (not a default; uses Nuvio's external
  player handling).
- Live TV menu: reorder and hide items like categories; Settings, Search channels and
  Sources cannot be hidden; "Reset menu".
- Sources: Xtream expiry date and connections shown per source ("Expires 12-Mar-27 ·
  2 connections"); warning on Sources and the Live TV menu from 7 days before expiry;
  Stalker if the portal provides it; M3U has neither.

## Device findings — 10 October 2026 (build `01f19bbed`, run 38017089840)

Checklist 7c done; findings (fixes in progress unless noted):
1. Stream info widget in the short stacked size leaves much empty space; show more.
2. Guide info panel: description stops after three lines with space left below; long titles
   ("Gordon Ramsay's 24 Hours to Hell a…") are cut off.
3. Live TV settings: opening Settings from Live TV jumps to Nuvio's main Settings (jarring).
   Move the Live TV settings out of the main Settings into the Live TV area, in its style.
4. Phone access needs the setup screen open on the TV; wanted: pair once on the TV, then the
   phone reaches settings and recordings without the screen open.
5. Multiview sometimes refuses another picture ("This TV box cannot decode another
   picture…"); avoid it.
6. Left from the guide sometimes shows the menu while focus stays on the programme; only
   leaving Live TV recovers.
7. Move Sport up next to Movies and Series in the Live TV menu.
8. Sport page guide should cover 24 hours.
9. Question: what decides the Sport only channels (channels with a matched game inside the
   visible guide window, plus the focused one); wanted: choose categories/channels to
   include or exclude.
10. Question: what decides "Watch on …" and "N other feeds" (matches ranked by match type,
    then programme start nearest kick-off, then channel order; hidden categories skipped);
    wanted: choose preferred/excluded categories or channels.
11. Question: are .ts recordings the best format, also for VLC on the phone (answered: yes,
    MPEG-TS is a straight copy of the broadcast, survives interruptions and plays in VLC;
    MP4 would need a remux at the end).
Passed otherwise (SMB recording worked).
13. (added later, fixed) Left/Back on the Recordings screen does nothing; should return to Live TV
    with the menu open.
14. (added later, fixed) Multiview channel/category overlay: focus can drop behind the overlay onto
    the multiview pictures when moving Left/Right between the category and channel lists.
12. Recording file names: date as dd-MMM-yy and no id suffix (done: `RecordingFiles.name`
    takes a `taken` check; the recorder checks names of other recordings and local files).

## Device findings — 9 October 2026, second pass (build `8354d89`, run 37867719176)

The 7b list passed apart from the items below (all fixed in code on 10 October, `f002c10`..head). Five screenshots were shared
(guide top area; Sport with clipped text). Items:
1. Checklist 7b otherwise passes.
2. Phone setup: after adding a source the page says "Saved to TV" and the option to add a
   separate XMLTV guide is gone.
3. Guide top area too busy: remove "Live TV · <profile>", "Updating programme guide…" and the
   clock from it; keep programme info, description, widgets and the corner picture.
4. 4K channel in the corner picture: sound but no picture ("This picture shows in full screen
   only"); the user wants the picture (the logo was the fix for the 8 October AM9 stutter).
5. Stream info widget: sparse, three basic stats; wants more information and a better look.
6. Widget layout "3" does nothing (likely no room); wants widget sizes (short, square, tall)
   and a single non-wide option.
7. Sport: text clipped in the middle area (section headings above "Sport only", e.g. "Close
   games", "Today", day headings) and card borders clipped at the top.
8. Sport fixtures on by default.
9. Refresh all sources at once and all guides at once.
10. Loading a new source or guide still feels slow.
11. Question: what "Tune" means in the stats overlay (time from starting a channel to its
    first picture).
12. Full screen: Left or Right also changes channel (Up/Down should be the only zap keys).
13. Back from full screen to the guide: the corner picture and the whole UI stutter while
    sound plays smoothly.
14. Holding Up from low in the guide: focus jumps to the widgets before reaching the top row.
15. Left/Back from the guide opens the menu on Favourites; focus should land on the first
    item (Search channels).
16. Full screen → Add to multiview: playback stops and restarts in the first pane; wanted
    seamless.
17. Sport multiview: the add list should offer all channels (Left from the channel list
    opens categories).
18. Multiview: the second pane sometimes has no sound although the same stream has sound in
    full screen.
19. Multiview: adding a third stream from a second source (first allows 2 connections, second
    1) did nothing.
20. Recordings play in a different player UI from the normal player.
21. 4K HLG HDR channels with AAC audio are very quiet.
22. "Leave Live TV?": Leave to Home should be first and focused.
23. Sport hero (e.g. Arsenal v Leeds United): large empty space beside the teams; bigger logo
    and names; question: what "4-0-1" / "2-3-0" mean (season record; wins-draws-losses for
    football per ESPN).
24. Golf leaderboard tile and hero: scores not right-aligned; hero has much empty space.
25. IPTV movie/series pages look empty; wanted: Nuvio's detail page even when signed out.
26. Main Settings → Live TV → Sport → TheSportsDB key: the Show button is not aligned with
    the key field, and the dialog and its field are see-through (dialogs outside Live TV
    miss the opaque floor).
27. (10 October) Sport panels look almost identical to another app's (screenshots compared);
    redesign wanted. Three directions on a design canvas (A scorebug bands, B ledger, C
    momentum). User's choice: B's whole top panel + A's cards with team logos (being built;
    the condensed numeral face is not used: font downloads are blocked and no font is added).
28. (10 October) Include every league we parse: NBL, WNBA and College Football added (off
    by default); NBL/WNBA use 4×10-minute quarters.
Further screenshots (10 October): golf hero with uneven score positions and empty space; the
IPTV series page (MobLand). New ESPN capture (10 October morning, NHL and PGA live) being
turned into trimmed real fixtures.
Also seen in the screenshots: the guide grid showed "Programme information unavailable" for
every 4K channel while the info panel had the current programme (guide update in progress);
the Premier League logo is nearly invisible on black; Arsenal v Leeds still "No channel found".

## Device findings — 9 October 2026 (build `374e1f6`, run 37845595141)

All 14 fixed or answered in `495a527`..`8354d89` (CI green run 37867719176, not device-tested);
item 10 needs the user to add the LOCAL_PROPERTIES_BASE64 repository secret. Also added: widgets
in the top area, "Find channels using" (guide/broadcasters/both). Items:
1. Some menus/panels/pop-ups transparent again (defaults: Solid panels off, glass floor 0.9).
2. Left on Live TV Sources (first page on opening) does nothing.
3. Back from the guide leaves Live TV for Nuvio Home; wanted: Back opens the Live TV menu,
   a further Back asks to confirm leaving to Nuvio Home.
4. Guide: programme text slightly larger; Compact density by default; shorter guide so the
   corner picture can be bigger; ideas for the empty middle of the top area (world clocks
   suggested by the user).
5. "Previous player has not confirmed it stopped" still appears when scrolling and pressing OK.
6. Stats HUD shows "HTTP/3 advertised": what it means and whether to pursue HTTP/3.
7. Full screen: Left opens the channel list (good); a second Left should open categories.
8. Back from full screen goes to the guide, then Nuvio Home; wanted: guide → Live TV menu →
   confirm leaving.
9. Left on the IPTV Movies and Series pages does nothing; should open the Live TV menu.
10. Nuvio QR sign-in missing in the CI build: CI's local.properties secret
    (LOCAL_PROPERTIES_BASE64 with NUVIO_SUPABASE_URL/ANON_KEY, TMDB etc.) is not set in the
    fork, so these features are built without keys. User action needed (repository secret).
11. Recording names still contain decorative characters.
12. Opening an IPTV movie/series: "Tried meta addons: Cinemeta. None of them provided metadata
    for id=tmdb:1401539" — need a fallback when no addon supplies metadata.
13. Could not find the phone setup page.
14. Sport card "No channel found" for Arsenal v Leeds tomorrow; how far ahead matching works;
    match the guide's days and allow recording ahead from match cards.

## Device findings — 8 October 2026 (build `f29cbe2`)

User-reported, with status:
1. Guide (EPG) empty — causes found: provider guide fails on the 256 MB cap, added XMLTV
   guides are never linked to the source, guide choice ignores whether a guide has
   programmes, a dropped reload. Fixed by the guide overhaul: every guide stores
   programmes only for channels in linked sources; "Guide days" setting (default 1 back,
   3 ahead); guide schema 6 (integer stage, rowid key, ~400 B per programme instead of
   730-1,250 B); cap 256 MB to min(2 GB, 25% free) with per-guide budgets; old copy
   deleted in chunks, incremental vacuum; new XMLTV guides linked automatically; per
   channel the first linked guide with programmes in the next 6 h wins. Upgrading from
   schema 5 drops programme data once; guides re-import on first open.
2. Guides load slowly and one after another — fixed: guides run as their own jobs, two
   imports at a time, 5,000-row transactions with reused statements, downloads to a temp
   file first. Host measurement: 20,000-channel x 3-day file with 1,000 linked channels,
   10.7 s and 24 MB on a desktop JVM; expect several times slower on a box.
3. Live TV menu → Settings opened Integrations — the Settings rail's own focus cancelled
   the requested category. Fixed in `2d72733` (requested category wins until its pane
   takes focus).
4. Settings location: user asked whether to move Live TV settings into Content &
   discovery. Decided (user): keep Live TV as its own category.
5. Full screen: Left opens the channel list, second Left did nothing — now closes it
   (`2d72733`).
6. "The previous player has not confirmed it stopped" on channel change — close waited
   up to 15 s for all reader threads; now completes once no provider request is running
   (`2d72733`). Needs a device recheck.
7. Corner preview: automatic preview removed; OK plays in the corner, OK again full
   screen; the "Live" tag only shows for the channel actually playing (`2d72733`).
8. Channel names cut off in the guide; menus, guide and Sport look generic — done in
   `8d47359` (wider channel column, 2-line names, less padding, V2 rail and focus
   styling) and `bbfa0a5` (Sport hero card and fixture cards).
9. Real XMLTV file refused ("could not be accepted") — likely strict timestamps or
   orphaned programmes. The parser now accepts timestamp variants and case/space
   differences in channel ids, keeps programmes without a channel element, and each
   refusal has its own message. The file could not be downloaded here (network policy),
   so this needs a device recheck.
10. Provider guide "not enough space" — see 1.
11. Recording from the guide gives no choice of location — record dialog now asks where
    (`8d47359`).
12. Sport view: no way back — Back returns to the previous view, Left on the first
    fixture opens the menu (`2d72733`). Sport redesign with logos, live scores (with a
    "Show scores" setting), venue, game clock and favourite teams in `bbfa0a5`.
13. Menu item renamed to "Settings" (`2d72733`).

## Device findings — 8 October 2026, second pass (build `24d6868`)

Overall OK. Items and status:
1. Dropbox XMLTV guide: "server address is not valid" — Dropbox redirects to a URL with an
   empty `#` fragment, which the guide and metadata clients rejected. Fragments are now
   dropped (`c718fab`, with a test).
2. Live TV Sources: Back and Left did nothing — the screen never called its `onBack`.
   Back now leaves; Left from the leftmost item leaves (`c718fab`).
3. Live TV ↔ Settings transition janky — the sidebar page layout was recreated on every
   route change (62 dp jump mid-fade) and Live TV start/stop ran twice; both fixed.
4. Live TV colour themes — now the app's own list incl. unlocked supporter themes;
   IptvTheme now provides the theme name, extended colours and colour scheme, so the
   choice applies.
5. Channel start ~4 s — one shared OkHttp client and pool (30 s keep-alive,
   retryOnConnectionFailure on), tune data in one DB pass, address resolved before the old
   player closes, release no longer blocks 500 ms, pre-warmed DNS+TCP after 400 ms focus
   (no request sent). Tune log gives prepare/close/play/total ms for device timing.
6. First OK after scrolling — most likely the alternating close failure (8); the row also
   consumes OK key-down now.
7. Behind live — TS measured only delay added after start (LiveBehindClock); HLS start
   seek removed; Right opens controls with Return to live focused when behind live.
8. "Previous player has not confirmed it stopped" — close() evicted the OkHttp pool on
   the main thread (TLS shutdown → NetworkOnMainThreadException, alternating), and a
   500 ms ExoPlayer release timeout left releaseFailed set for good. Fixed; close is
   confirmed when the playback thread ended and no provider call is open; silent retry
   up to 3 s; log lines `live close ms=` / `live close unconfirmed reason=`.
9. 4K HLG HDR corner stutter/reboot — corner and inset never attach a TextureView for
   HDR or >1080p (logo + "press OK" with audio); switches wait for the old playback
   thread so decoders never overlap.
10. Recording names: decorative superscripts (e.g. "ᴸᶦᵛᵉ") were kept; now stripped and
    names NFKC-normalised (`c718fab`). Colons and length were already handled.
11. Transparent panels — glass panels get a minimum body opacity in Live TV (0.9, 1.0
    with Solid panels); menu and guide share panel colour and text styles.
12. VOD screens restyled with the app's poster cards and detail-page layout.
13. Sport fixtures — fetched when Live TV opens, cache first, placeholders reserve the
    space, channel matching runs separately. Parsers checked against real responses
    (NRL running totals, NRL form strings, cricket innings fixed).
14. Sports data fields reference — `IPTV-SPORTS-DATA-FIELDS.md`, from real responses.
    Sports experience concepts (hub, HUD, Game Centre, stats multiview, follow/alerts) in
    a design canvas; TheSportsDB premium key kept out of the repo (relay suggested).
15. Defaults: Solid panels now off by default; Pure black and background artwork were
    already off.

## Planned but not done (7 October 2026; history)

Everything from the 7 October comparison is now built except:
- Recording: crash/power-loss recovery validation on a device.
- Playback: device measurements for local timeshift and the safety buffer (memory,
  storage write rate, AC-3 passthrough with 0.97x).
- Guide: Live TV rows on the Nuvio Home screen.
- Validation: device fixtures, schema upgrades on an existing install, subtitles/CC/
  teletext, accessibility, minified build on a device.

## User feedback after the first device pass — 7 October 2026

Seventeen items from the AM9 run of 5276c31. Status (all host-tested where testable,
CI-built; none device-tested yet):
1. Guide form Save/Cancel unreachable — buttons now inside the scrolling form.
2. Provider guide "unexpected format" — channel entries without an ID are skipped
   (6ef6eb2).
3. Connections automatic — Xtream `max_connections` applied unless a number is
   chosen (`IptvSourceConnections`, per-source keys in `IptvLivePreferences`).
   Sources set manually before this change count as Automatic once.
4. Preview plays the channel in focus after 800 ms (setting "Play in the preview").
5. Slow start — the preview itself pre-warms; tune timings are now logged
   (`tune kind=… prepare ms=… play ms=… total ms=…`). No further speed change
   until those numbers come back from the device. Prefetching neighbours is not
   done: it would take a second provider connection.
6. Phone setup — served by the TV app itself (NanoHTTPD on the box, same Wi-Fi; no
   PC or server needed). Page redesigned; Live TV settings can be changed from it
   (confirmed on the TV).
7. Transparent layers — Live TV appearance of its own: solid panels and plain
   background by default, colour theme, pure black, optional artwork (`IptvTheme`).
8. Multiview fills the screen with 2 dp gaps; title shows for five seconds.
9. Hold OK on a category opens Open/Hide/Show options.
10. Recordings and Live TV settings moved to the top of the menu.
11. Back from the menu leaves Live TV; "Exit Live TV" at the top of the menu.
12. Quiet channel — per-channel volume boost (+3 to +12 dB, `LoudnessEnhancer`).
    The likely cause is the broadcast's lower loudness, not decoding.
13. HDR in the preview — the corner picture uses a TextureView so the TV should stay
    in SDR until full screen. Unverified: some Android 14 builds may still switch.
14. Sport empty — caused by item 2.
15. Preview focus — Up from the guide focuses the picture; OK opens full screen.
16. Arrow keys in full screen dropped to the guide — a failed zap now keeps full
    screen and shows the reason (likely cause: the new channel failed to open).
17. Recording to local storage works. The 70 s recording in the log was a stop
    (user or scheduled end), not a lost connection; logs now say which.

## Device findings — 7 October 2026 (AM9, CI build 5276c31)

- Xtream refresh is fast now: 13,745 channels downloaded in 5.2 s and saved in 5.2 s
  (six 2,500-row chunks), down from minutes on 6 October.
- The provider guide failed twice with "Missing guide channel ID": the provider's
  XMLTV has a `<channel>` without an `id`, which rejected the whole guide. Fixed: such
  channels are skipped (host-tested); needs a device recheck.
- `StreamResetException` (HTTP/2 stream reset) appears in NuvioXtream lines and at the
  end of a 70-second recording that finished DONE; probably cancelled requests
  (zapping, Stop). Not yet confirmed.

## Device findings — 6 October 2026

First AM9 run of the CI-built prototype with a real Xtream account (13,740 live
channels, 4.8 MB list, 145 MB provider guide taking over two minutes to download).

- A wrong username produced the generic failure message. Source errors now name the
  failed step (address, network, HTTP status, redirect, size, format, sign-in) and the
  Xtream log line includes the HTTP status. Server and username are no longer masked;
  the password has a Show/Hide toggle; missing `http://` is added; credentials are
  trimmed; a new source loads its channels on save.
- Refresh sat on "Working…" for minutes and no channels appeared. Causes found in
  code: one Keystore operation per channel row, and the automatic provider guide
  (capped at 64 MB and a 60 s call timeout) ran inside the same operation. Fixed:
  envelope sealing (`EnvelopeIptvSecretBox`), Xtream rows stored without the login
  (`IptvXtreamClient.streamUrl` adds it at tune time), provider guide refreshed in
  the background with 512 MB, 50,000-channel, 2,000,000-programme limits and a
  15 min call timeout. Why no channels appeared is not yet confirmed on device.
- Empty Live TV explained (user report): an M3U playlist with no channels was added
  first and the real Xtream source second; that build opened the first source.
  4d74967 opens the first source with channels, and sources and guides can now be
  removed from Live TV sources (with confirmation; a running refresh is cancelled
  first, document grants are released).
- Local Windows builds hit out-of-memory with the 6 GB Gradle heap; use the CI APK.
- The Live TV and Sources redesign the user asked for is done (see the work log);
  the device check of the refresh timing and provider guide is part of the device
  pass in the work list.

## Target devices

Android 9 to 14 (API 28–34), 2–4 GB RAM: Amazon Fire TV Stick 4K Max 2nd gen (Fire OS 8,
Android 11, no Google services), Nvidia Shield Pro, Xiaomi Box S 3rd gen, TVs with
Android TV / Google TV built in, and the user's own Ugoos AM9 Pro (the main test device;
4 GB RAM, Android 14 assumed — unconfirmed). Guard every API above 28, keep memory per
player small, and size multiview from `IptvDeviceProfile` (memory, low-RAM flag,
hardware AVC decoder instances) rather than fixed numbers.

## Next session — work list (7 October 2026; history)

Status on 7 October 2026 (branch head after the work list; nothing device-tested):

1. Small gaps — done (fd03110). Removing a source removes linked guides no other
   source links (`IptvXtreamGuides.removeSource`; covers M3U header guides; the
   confirmation text says so). `IptvLivePreferences` (data/iptv) owns the
   `iptv-live` keys; source removal, profile removal and "clear all profiles" drop
   that profile's/source's keys (`LivePreferenceKeys`, host-tested); multiview keys
   stay. The v2 catalogue upgrade test now builds a real v2 schema (the old one set
   version 2 on a current database and would fail with duplicate columns). Upgrade
   tests 7→8 (catalogue) and 3→4 (guide) already existed; 4→5 (guide) added. Add
   guide has a Guide folder chooser listing XMLTV files in the `iptv-guides`
   folders (internal storage and USB), with the folder paths shown when empty.
2. Sport — done, guide-only (ff1a94b, review fixes in the next commits). XMLTV
   `<category>` kept (8 per programme); `SportsGuide` marks sport at import
   (categories, competitions, fixture titles with a live marker / sports channel /
   sport word; films, documentaries, magazines, talk, news, highlights excluded);
   guide schema 5 adds `programmes.sport` with partial index (stage,start,stop);
   upgrading clears `feeds.refreshed_at` so guides re-import on first open (provider
   guides follow their source's 12 h refresh). Sport rail entry lists channels with
   sport on now or within 6 h, live first; reloads every 5 min. No external data,
   no reminders.
3. Timeshift — done as catch-up-based pause/rewind (bcb8742 + review fixes).
   `LiveTimeshift` (host-tested): resume after > 20 s on archive channels continues
   from the paused minute; Rewind on live goes back about a minute and keeps
   stepping back while behind live; the timeshift stream runs an hour past
   max(programme end, now); a failed archive tune returns to live with a message.
   Non-archive channels resume in place while the player holds the stream. The
   paused state follows the player (audio focus, headphones). Options weighed and
   not built: an ExoPlayer back buffer (it counts against the 12 MB target buffer,
   so minutes of 1080p need 60+ MB of heap per player: too much for 2 GB boxes and
   multiview), and playing a growing recording (needs a second provider connection
   or the unvalidated capture chain). The capture chain stays disabled.
4. Translations — done for all 40 locale folders (`values-ar` … `values-zh-rTW`,
   including `zh`, `pt`, `b+es+419`, `b+sr+Latn`; the earlier "38" was a miscount)
   as `values-<locale>/iptv_strings.xml`, `iptv_setup_strings.xml`,
   `iptv_recording_strings.xml` (the 12 `iptv_*` entries from `values/strings.xml`
   are in each locale's `iptv_strings.xml`). Checked by script for names,
   placeholders, apostrophe escaping and plurals (CLDR forms per language);
   spot-checked by hand, not reviewed by native speakers. Any new English IPTV
   string must be added to all 40.
5. Release-variant check — the PR Full Debug Build workflow has a `build_type`
   input (`debug` default, `release` builds the minified APK with the throwaway CI
   key). IPTV code uses no reflection or serialisation libraries; NanoHTTPD and
   Media3 already have keep rules; no IPTV keep rules added. The minified
   `iptvPrototype` release APK built cleanly at `d8d4a03` (run 37550880901; R8 with
   all feature code; the later review fixes add no reflection). Not installed or
   smoke-tested on a device; download `app-iptvPrototype-arm64-v8a-release.apk` from
   that run to try it (signed with the throwaway CI key).
6. Second pass — done. Independent review of this session's diff (10 findings, all
   fixed; see the review file); host tests core 349, data-layer 152, device tests
   compile; CI debug build green at `f465328` (run 37552365804).

Decisions on 7 October (user): the main-menu item is called "Live TV"; IPTV settings
live on the Live TV side, not in the main Settings. Done: Live TV settings screen
(`IptvSettingsScreen`/`IptvSettingsViewModel`, route `iptv/settings`, opened from the
bottom of the Live TV menu; device-wide values in `IptvLivePreferences` with
`settings-*` keys, multiview keys unchanged); the main Settings IPTV category was
removed (Settings files are back to `main`). If IPTV ships "enabled in full, off by
default", that on/off switch is the one IPTV item that must sit in the main Settings.
How IPTV ships is still the user's decision.

Then the user's device pass (Ugoos AM9 Pro first, then smaller boxes):
- Install the CI APK (`app-iptvPrototype-arm64-v8a-debug.apk`; uninstall an older
  prototype signed with another key first — this clears its sources).
- Add the Xtream account; time the refresh; check channels, the provider guide, logos,
  catch-up, search, now-on search, multiview (2 and 4 pictures, both layouts, a 4K and
  a 1080p TV), recording (now, scheduled, across a reboot), phone setup.
- Logs: `adb -s <serial> logcat -d -s NuvioIptv NuvioXtream` after a refresh.
- Device fixtures: see "Next steps" below (`tools/iptv-device-tests`).

Before merge and release (only on the user's confirmation):
- Clean device pass; minified release build with IPTV enabled built and smoke-tested;
  upgrade from an existing install checked (catalogue schema to 8, guide schema to 5).
- The user decides how IPTV ships. The release workflow builds only `fullRelease`,
  where `FEATURE_IPTV_ENABLED` is false. Options: enable in `full` (move the recording
  permissions, service and receivers from `src/iptvPrototype/AndroidManifest.xml` to
  main), keep the separate prototype app, or enable in `full` behind an off-by-default
  setting (suggested). Decided 8 October 2026: part of the full app.
- Merge (user, 9 October 2026, replaces 8 October): squash-merge `iptv/player-binding`
  into `main`; the user then retires `iptv/player-binding`, `iptv/wip` and `nuvio-test`. No
  history rewriting. The workflow change and `tools/iptv-host-tests` ship with that merge.

## Rules for this branch

- Work on `iptv/player-binding` only. No pushes to `main`, no pull requests,
  releases, branch deletion, or GitHub comments, reviews, issues or discussions.
  GitHub use is limited to pushing commits and starting or reading the
  `PR Full Debug Build` workflow.
- Commits as `ysosrs123` / `ysosrs123@users.noreply.github.com`, unsigned, no
  trailers, in the project's own voice; no tool attributions anywhere.
- No explanatory comments; minimal or no KDoc; match the surrounding style.
- No dependency or version upgrades. WorkManager is not a dependency; NanoHTTPD 2.3.1
  and ZXing are; smbj 0.15.0 was approved by the user.
- Original work (user rule, 10 October, revised the same day): our work must not look
  taken from other apps or developers. Do not copy another IPTV/media app's design,
  layout, wording or code (e.g. other IPTV players, sport apps, other Nuvio forks); design
  from our own data and style, and treat a competitor screenshot the user shows as what to
  avoid. Naming other apps is fine where it is functional or factual (user-agent presets
  such as VLC/Kodi/IPTV Smarters, "Open in VLC", playlist tags `#EXTVLCOPT`/`#KODIPROP`,
  protocol names, data sources ESPN/TheSportsDB). Genuine licence and attribution comments
  (GPL, MIT, Apache etc., e.g. code ported under a licence) must stay. Do not strip
  references wholesale; the test is whether something reads as copied, not whether a name
  appears. Give helpers this rule in their brief.
- Nothing may look machine-written: no mention of automated tools or assistants in code,
  comments, docs, commit messages or branch names.
- Keep this handover, the progress log and the release notes current with every change;
  the user has asked for this explicitly. Any commit that changes what the app does
  updates IPTV-RELEASE-NOTES-DRAFT.md in the same commit (the right section, the known
  limitations and the validation line with the latest CI run). Design-only work is noted
  in the release notes only once it is built. Before handing over to a new session,
  bring "Start here" up to date and tell the user.
- Never store API keys (e.g. the user's TheSportsDB key) in the repo, docs or app.
- The user decides product questions (shipping, data sources, keys); give a
  recommendation and wait.
- Docs carry no local paths, device IPs or hostnames. Australian English.
- Say what is host-tested, CI-built and device-tested separately.

## How to build and check

See "How to build and check" in "Start here". The host tests use desktop org.json, so
Android-only differences (no `keySet`) and all Compose/Hilt code show only in CI.

## Code map (app/src/main/java/com/nuvio/tv)

- 12 October, live player: steady behind-live figure and its show/hide flag (hint, Return to
  live, Stats) `core/iptv/LiveBehindGauge.kt`; behind-window rejoin `LiveRetry.rejoin` in
  `LivePlaybackPolicy.kt` and `LiveRejoinGuard` in `LiveReconnect.kt`; local timeshift
  display clamp `LocalTimeshiftPolicy.held` in `LocalTimeshift.kt`.
- 11–12 October: recording playback `core/iptv/RecordingTimeline.kt` (time index),
  `RecordingVodPlaylist.kt` (HLS byte-range playlist), `data/iptv/IptvRecordingReader.kt`,
  `IptvRecordingStore.kt`, `IptvRecordingCopier.kt`, resume `IptvRecordingResume` in
  `ui/screens/iptv/IptvRecordingsViewModel.kt`; guide catch-up window `core/iptv/GuidePast.kt`;
  multiview strips `core/iptv/MultiviewStrip.kt`, `ui/screens/iptv/IptvMultiviewStrip.kt`;
  sport parsing `core/iptv/SportsFixturesParsers.kt`, `SportsSummary.kt`, `SportsDetail.kt`,
  `SportsDbExtras.kt`, `SportsScoreDays.kt`; sport screens `IptvSportHero.kt`,
  `IptvSportCards.kt`, `IptvGameCentre.kt`, `IptvSportsOverlay.kt` (score bug),
  `IptvAllScores.kt`, `IptvSportsLeaguePicker.kt`, `IptvSportsSettings.kt`; summaries
  `data/iptv/IptvSportsSummaryClient.kt`.
- 10 October: widget layouts and stream facts in `core/iptv/LiveWidgets.kt`; refresh batches
  `core/iptv/RefreshBatch.kt`; sport records and countdown `SportsRecords.kt`,
  `SportsCountdown.kt`; recordings stream `core/server/IptvRecordingStreamServer.kt`; IPTV
  detail-page meta `IptvVodMeta`/`IptvVodMetaSource` in `ui/screens/iptv/IptvVodServices.kt`;
  handover into multiview `LivePlaybackRuntime.handOver`, `LiveSessionAdmission.resize`;
  opaque settings dialogs `IptvOpaqueDialogs` in `IptvTheme.kt`.
- Sports (8–9 October): see "Sports build summary" under "Earlier session notes" for every file; widgets in
  `core/iptv/LiveWidgets.kt` and `ui/screens/iptv/IptvWidgets.kt`; IPTV movie/series detail routing
  in `core/iptv/VodDetailRoute.kt`; recording-name cleaning in `core/iptv/LiveRecording.kt`
  (`RecordingText`); pending sport recordings in `core/iptv/SportsPendingRecords.kt`.

- New on 7 October: `core/iptv/SportsGuide.kt`, `LiveTimeshift.kt`,
  `LivePreferenceKeys.kt`; `data/iptv/IptvLivePreferences.kt`; translations in
  `res/values-*/iptv_*.xml`.
- `ui/screens/iptv/IptvSettings*`: Live TV settings (playback, guide, multiview,
  recordings; links to sources, phone setup and recordings).
- `ui/screens/iptv`: `IptvLiveScreen` (scaffold, info panel, preview, rail, empty
  states, channel menu, guide picker, search), `IptvLiveViewModel` (state, playback,
  multiview, recording actions, `iptv-live` preferences), `IptvGuideGrid`,
  `IptvLiveFullscreen` (banner, number entry, channel panel, catch-up keys),
  `IptvLiveControls` (player control deck with the saved `PlayerControlLayout`),
  `IptvStatsOverlay` (feeds `PlaybackStatsOverlay`), `IptvLiveParts` (shared surfaces,
  logos, programme helpers), `IptvMultiview`, `IptvTrackDialog`, `IptvLivePlayback`
  (ExoPlayer wrapper: redirects, reconnect, `limitHeight`), `IptvSources*`,
  `IptvRefreshCoordinator`, `IptvLiveLaunch`, `IptvSetup*`, `IptvRecordings*`.
- `core/iptv`: models and pure logic (guide grid, matching, admission, device profile
  `DeviceProfile.kt`, `MultiviewSizing.kt`, `LiveRecording.kt`, setup drafts and
  pairing, capture chain).
- `data/iptv`: stores (catalogue schema 9, guide schema 6, VOD schema 2, recordings JSON), clients
  (M3U, Xtream, Stalker, XMLTV), `IptvXtreamGuides`, `IptvCatchup`,
  `AndroidDeviceProfile`, `IptvProfileAccess`.
- `core/recording`: `IptvRecorder`, `IptvRecordingService`, `IptvRecordingAlarms`.
- `core/server`: `IptvSetupServer`, `IptvSetupWebPage`, `IptvSetupAddress`.
- `core/di/IptvModule.kt`: device profile, admission limits, short guide.
- Navigation: `Screen.IptvSetup`, `Screen.IptvRecordings`, Live TV drawer item in
  `MainActivity` when `FEATURE_IPTV_ENABLED`.
- Strings: `res/values/iptv_*.xml` (about 50 files; each round of work adds a new one,
  e.g. `iptv_ui23_sport_strings.xml`, translated in the matching `values-*` folders).
  Recording manifest entries: `src/iptvPrototype/AndroidManifest.xml`.

## Open questions and risks

- Unverified on devices (see "Start here" for what has been tried since): what `Display.Mode` reports on
  each box (some may report the UI size, which makes multiview pick lower qualities);
  real multi-decoder capacity; Fire OS exact alarms in deep sleep; recording across
  stream gaps; schema upgrades on existing installs; R8.
- Sport depends on guide text; check it with the user's real provider guide (are
  categories present, are fixtures titled "A v B"?). Timeshift depends on the
  provider's archive delay; a fresh pause may hit "no recording yet".
- Translations are done by the developer, not native speakers; wording may need polishing.
- Android's XML parser may reject unknown entities such as `&nbsp;` in guides.
- Xtream catch-up uses the device time zone (the server zone is not stored).
- The guide shows about four to five rows at 1080p (the top area takes 188 dp).
- Channel panel and control deck focus need checking with a real remote.
- The short guide uses `java.util.Base64` (API 26, fine for API 28+).

## Work log — 6 October 2026

Done since 1b7952c (nothing device-tested):
- Data layer: chunked catalogue save into an unpublished generation with one publish
  step; remove source / remove guide; logos for every source kind; feed-suffix guide
  ids; catch-up addresses (`IptvCatchup`); header guides, caps, short guide, format
  detection (the 64 MB parse cap was the likely cause of "unexpected format").
- UI rewrite in the fork's design language (see the code map); Live TV drawer entry;
  channel search; landing category, hidden categories, favourites order; number entry
  beyond loaded pages; sticky removal of automatic guides.
- Now-on search (guide schema 4 `search_title`, catalogue schema 8 `epg_id`/`epg_base`/
  `name_key`; rows fill on the next guide import or source refresh; programmes longer
  than 24 h are not found).
- Multiview: up to four tiles, each its own `LivePlaybackRuntime` sharing the
  admission; tile count from `IptvDeviceProfile` (memory, low-RAM flag, AVC decoder
  instances, decode budget from `PerformancePoint.covers` on API 29+ or
  `areSizeAndRateSupported` on 28); `MultiviewSizing` turns each picture's physical
  height (UI size × `Display.Mode` physical height ÷ UI height) into 360/540/720/1080
  by quality (Automatic/Sharpest/Lightest) and steps non-focused pictures down to fit;
  `IptvLivePlayback.limitHeight` applies it without re-tuning (HLS ladders only).
  Layouts Grid and One large (`mainTile`).
- Per-source connections: new sources get their own account `src-<sourceId>`; keys
  use `admissionAccount(profileId, accountId)`.
- Recording: foreground `dataSync` service capped at 6 h, exact alarms
  (`USE_EXACT_ALARM` 33+, `SCHEDULE_EXACT_ALARM` up to 32), boot re-arm, JSON store,
  own connection per recording, storage check, alarm settings prompt.
- Phone/PC setup: NanoHTTPD server, QR code, pairing token and six-digit code, CSRF
  and origin checks, confirmation on the TV; security-reviewed and fixed.
- CI green: 105791e (37467974960), ef6f3d7 (37469731575), dccb7fb (37472049158),
  f435994 (37474346067), 2484181 (37537338609), 73b59de (37545837449).
- Host tests at 73b59de: core 340, data-layer 151.

## Redesign work — 6 October 2026

Items 1–4 of the redesign plan are implemented; none is device-tested.

1. `IptvRefreshCoordinator` (app-wide singleton, one heavy refresh at a time, stage
   statuses, stale refresh on screen open: sources 12 h, guides 6 h, failed attempts
   retried after 30 min). WorkManager is not used: it is not a dependency of the app.
   Catalogue schema 6 adds `refreshed_at`, `position`, `category`; guide schema 3 adds
   `refreshed_at` and `channel_names`.
2. Guide-first `IptvLiveScreen`: row-focus guide grid with shared time cursor, 12-hour
   window from 30 minutes ago, category rail, channel options, full screen on the same
   player. Channels page in 60s and append near the end of the list.
3. Name matching (`GuideNameMatching.kt`), linked order as default guide priority
   (changes the earlier "ambiguous until explicit priority" rule), Move up in Sources,
   guide channel picker.
4. Full-screen banner with quality badges, channel panel with recent channels and
   schedule, last channel, number entry.

Next from the study: secure phone/PC setup, playback hardening (behind-live-window
recovery, live retry backoff, HLS live speed), catch-up, search, sports, multiview.

## Small open items

- `suggestAccountGroups` distinguishes only Xtream and M3U; Stalker sources should
  group by portal host and MAC (as Xtream with the MAC as the user).
- The workflow changes and `tools/iptv-host-tests` ship with the merge into `main`.

## Next steps (device fixtures; earlier plan)

1. Install the CI APK on the AM9 and check Live TV. Build `tools/iptv-device-tests`
   locally (not built in CI).
2. On the AM9 rerun: `CaptureVideoPlayerAndroidTest` (both cases),
   `LocalCaptureSampleStagerAndroidTest`, `TsCaptureDecodeAndroidTest`,
   `IptvGuideStoreTest`, `IptvCatalogueStoreTest`, then the full fixture suite.
   A player timeout now fails with a state dump (player, window, decoder counters,
   reader, transport, renders).
3. Time a large catalogue refresh on the AM9 (review item 12) before redesigning
   per-row Keystore sealing.
4. Then measure aggregate memory and storage margins, validate renderer preroll
   discard and seek acknowledgement, and only then enable capture controls.
5. Once steps 1–2 pass, the user has a separate session merge `iptv/player-binding` into
   `main` as a squash merge (see "Start here" item 7); the user then retires the old branches.

## Logic layer added for the next UI work — 6 October 2026

Host-tested (core 238, data-layer 121); the screens that use them are not built yet.

- Guide grid: `GuideGrid.kt` lays out a slot-aligned time window per channel
  (clamping, overlap trimming, no-information gaps, open-ended programmes, focus time
  anchor). `IptvBrowseRepository.guideRows` loads a page of channels into rows.
- Automatic Xtream guide: after a published Xtream refresh the source gets a linked
  feed whose endpoint is `xtream-guide:<sourceId>`, resolved at refresh time to the
  provider's `xmltv.php` from the encrypted connection. Large provider guides may hit
  the 64 MiB parse budget.
- Account groups: catalogue schema v5 adds source positions and per-profile account
  groups (label, 1-16 streams). The live runtime applies the group limit at open.
  `suggestAccountGroups` proposes groups by provider host (and Xtream user).
- Stalker Portal: `STALKER` source kind (MAC in the connection's username field),
  handshake/profile/genres/channels refresh, `create_link` at tune time. Profile
  status codes other than 0 fail closed; real portals still need checking.
- Local guide import: `.xml/.xmltv/.xml.gz/.gz` files in the app's `iptv-guides`
  folder on internal storage or USB are listed and used via `file:` endpoints;
  paths outside those folders are refused.

UI still needed for these: guide grid screen; Stalker option and MAC field in the
source form; account group editing and source ordering; local guide file chooser;
showing the automatic Xtream guide in the feed list.

## Review fixes — 6 October 2026

Branch iptv/player-binding. Fixed in source:

- Capture/period: final frame kept for declared-length video PES (packet-fed
  extractor clears video PES lengths after hashing); atomic current-snapshot borrow
  for createPeriod; live-tail skipData stops at the last keyframe; timeline keeps up
  to 4096 windows; boundary/stop errors raised only at the stream tail.
- Ownership: admission close tickets completed at non-owner release; transport close
  joins the worker and re-confirms the HLS source; failed body close retried; primary
  BACKPRESSURE/STORAGE_BLOCKED kept; later joiners start an unstarted transport.
- Ingest: overlay-less catalogue tombstones dropped (over-cap returns INVALID); XMLTV
  byte runs bounded before token construction; bounded guide quarantine and duplicate
  channel merge; folded search with schema v4 re-index; guide URLs withheld from logs;
  validators not sent after redirects; six redirects followed.
- UI: Sources/Live back stack; profile credential removal and Live session open off
  the main thread; background refresh keeps focus/programmes; extension renderers for
  live audio; guide document grants released when unused.

Item 12 fixed after device testing (see "Device findings"). Item 20 resolved by
the `FEATURE_IPTV_ENABLED` flavour gate. No change by design: 7, 8, 18, 21 (reasons
in the review).

Host verification (no Android SDK): core 222/222; data-layer JVM 117/117 across every
com.nuvio.tv.data.iptv suite (real stager/extractor, MockWebServer HTTP clients).
Each fix with a test was checked to fail without its change. All 18 IPTV androidTest
classes and IptvLivePlayback compile against the shipped Media3 classes with stubs.
Not compiled here: Compose/Hilt UI files (navigation, ViewModels, settings) and
ProfileManager; not run: full app compile, Gradle unit tests, device instrumentation.
Run those locally before trusting UI changes; device reruns needed for the capture
player, stager/codec, guide store and catalogue store fixtures.

## Player binding — 6 October 2026

Branch iptv/player-binding, derived from iptv/wip 8b84c11. The last device-validated
checkpoint remains b68985a.

Probable cause of both AM9 player timeouts, from code inspection and a JVM reproduction
(not yet confirmed on device): CaptureSampleBatchQueue (maxBatches 2) and
CaptureSampleLoadCursor (maxOpenInputs 2) stage at most two rows. The three-segment
fixture staged segments 0-1 and stopped at CAPACITY. Only tests called
retireConsumedPrefix, so no played row was ever returned; segment 2 was never staged,
the reader never reached ENDED and the video stream returned NOTHING_READ after about
4s. ExoPlayer stayed BUFFERING until the 30s wait expired.

- CaptureEpochMediaSource(retiresPlayedBatches = true) makes period discardBuffer
  retire rows wholly before the playback position through the existing checked
  retireConsumedPrefix path. Default remains off; CaptureVideoPlayer requires it.
- Media3 MaskingMediaSource replaces an explicit start of 0 with the window default
  position (the live edge). CaptureVideoPlayer startPositionMs is now nullable: null
  starts at the live edge; explicit positions are honoured, with 0 sent as 1ms, which
  frame-floors to the first retained frame.
- CaptureVideoPlayer.describe() and the fixture's timeout path now report player,
  window, decoder-counter, reader, transport and render state instead of a bare timeout.
  The fixture also asserts the first rendered frame comes from the requested start.
- Epoch boundaries and STOPPED readers no longer abort playback early:
  readDiscontinuity and SampleStream.maybeThrowError raise them only once the stream
  has played all staged rows (review finding 3a). Ownership loss still fails at once.

Host evidence: core 211/211 pass with the existing runner and pinned Kotlin 2.3.0.
A host JVM harness ran the 13 data-layer capture suites (66 cases: 64 existing plus
two new regressions) against the shipped Media3 AAR classes, Media3 1.8.0 lib-decoder
built from source and a default-value android stub generated from android-all. The new
cases fail without their changes and pass with them. The AM9 fixture compiles against the
same classes with a stub InstrumentationRegistry. These are not Gradle/AGP builds: the
Android SDK host is blocked, so no full app compile, full 317-case JVM
run, harness APK or device execution has been done for this change.

Next local steps: full app compile, full IPTV JVM run, harness build, then the two
CaptureVideoPlayerAndroidTest cases on AM9 using the existing README commands and
device rules. Controls stay disabled. Rendering, preroll discard, seek acknowledgement,
blocked-release retention and measured memory remain unclaimed until those pass.

## Implemented capture chain and important solutions

- Store: CaptureSegmentStore owns/exclusively locks a bounded directory, complete
  media/index sync plus atomic promotion, pinned anchor/successors, last-good rows,
  backpressure and retryable failed cleanup. Append copying/sync uses a separate
  writer lock so local snapshot/open/pin access works during slow input. Index
  publication/retirement still has bounded local IO under the state monitor.
- Ownership: SharedCaptureRuntime owns one actual transport/store and independent
  viewer/recorder consumers through one admission instance. Failed joins preserve
  existing consumers; uncertain closure retains infrastructure, account, memory,
  spool and pins. Matching keys alone do not prove real shared acquisition.
- Transport: SegmentCaptureTransport has one body/append/close at a time; committed
  sequence hints publish only AFTER atomic append returns. Bounded HLS source/HTTP
  ownership fences late/cancelled/lost responses, missing/changed overlaps, failed
  manifest-body closure and unsupported playlist resources. No failed-request retry.
  Critical finding: OkHttp close can swallow underlying IOException; a later no-op
  close is not release proof. Close the actual source, retain uncertainty/charge.
- Inspection: TsCaptureInspector supports a narrow single-program Baseline AVC /
  AAC-LC complete MPEG-TS profile with intact PAT/PMT/continuity, in-band stable
  init, initial IDR, bounded samples/PES/packets, coherent video/audio PTS. It is
  header inspection, not arbitrary coded-payload safety or a general codec decoder.
  CaptureTsInspectionIndex binds immutable evidence to exact committed row/owner;
  open media verifies length/hash through EOF and retains its exact input/pin.
- EOF bridge: platform and shipped HLS extractors each omitted final video sample.
  Shipped PesReader scratch reuse prevents final AVC flush. LocalTsSegmentExtractor
  is a scoped unchanged-AAR bridge; only hash-matched complete segments receive
  the synthesized empty-PUSI finalization. Output is tentative until success, so
  staging is transactional. No invented media frame or binary/dependency upgrade.
- Timeline/seek: CaptureSampleTimeline keeps actual PTS origin across eviction/wrap
  and explicit codec/capture/timestamp epochs. Actual video/video-audio phase, not
  EXTINF, determines positions. CaptureSeekController uses pending anchor in BOTH
  directions, rejects stale/expired commits and exposes explicit captured-tail.
  This is not wall-clock LIVE or actual player/render acknowledgement.
- Staging: LocalCaptureSampleStager checks verified EOF/formats/counts/keyframe/PTS
  and cancellation before publication. One video epoch preserves audio phase,
  including first audio -21333us in fixtures. Limits bound encoded bytes/init,
  samples and dimensions; transient/object/native/decoder budgets are not measured.
- Physical fences: fresh free-space/allocation-unit/volume observations plus
  explicit margins and worst-case pending/index overhead gate runtime admission,
  per-write and index publication. Host cluster4096 differs from JDK sector512;
  bounded Win32 observation fixed that. Observations are NOT OS preallocation,
  production AM9/USB/SMB margins, ENOSPC or physical power-loss certification.
- Finite reader/period: PinnedCaptureSegmentPeriod and PinnedCaptureReaderConsumer
  deliver one exact verified staged segment, frame-floor seeks and full batch IDR /
  audio preroll. Async stale/cancelled completion is fenced; failed/timed-out close
  shares one closer and retains handles/pins/reservations until confirmation.
- Incremental queue: CaptureSampleLoadCursor loads exact successors, retains ticket
  and waiting/terminal anchor pins, distinguishes WAITING/CAPACITY/ENDED/STOPPED /
  EXPIRED/DISCONTINUITY/FAILED/CANCELLED and never jumps/retries/crosses an epoch.
  CaptureSampleBatchQueue reserves worst-case next encoded batch/slot BEFORE open,
  publishes transactional stages, retains failed candidates and fences reentry.
- Async reader: IncrementalCaptureReaderConsumer has one IO worker/metadata
  observer, conflated capture/explicit hints and cached immutable batches/Media3
  metadata. Exact object/revision fences reject stale/foreign/retiring views.
  Declared encoded-resident floor applies BEFORE transport/consumer start.
- Growing period: CaptureEpochPeriod has one exclusive reader borrow. Live tail
  returns NOTHING, only exact COMPLETE becomes EOS. Growth retains cursor/PTS;
  seeks feed selected batch initial IDR/audio phase. Explicit consumed-prefix
  transfer checks selected tracks finished those rows, keeps one row and closes /
  refills off-thread; UID/epoch origin stays stable. continueLoading cannot create
  local tail polling. EOF and period closure do not acknowledge decoder shutdown.
- Source: CaptureEpochMediaSource is ACTUAL BaseMediaSource + OwnedCaptureConsumer,
  owning reader/metadata observer and one queued/in-flight playback callback via
  CapturePlaybackRefresh. Cached-only playback-thread access, exact current UID /
  target, unresolved initially empty running capture, explicit epoch errors.
  Preparation refreshes latest cache to cover queued-before-prepare races; period
  close clears own format/init references. Owned close requires observer/callback
  quiescence, period AND BaseMediaSource caller release and reader/input closure.
  Actual renderers must be stopped/confirmed separately; untested Native gates stay
  disabled. The direct foreground player still rejects capture sharing; UI not wired.

Current code: app/src/main/java/com/nuvio/tv/{core,data}/iptv; foreground/UI:
app/src/main/java/com/nuvio/tv/ui/screens/iptv. Inspect actual classes/APIs.

## Checkpoint/evidence map (under validation/)

| Commit | Added | Design + validation report stem |
| --- | --- | --- |
| 43b1dff | TS inspection, local reader, EOF bridge | IPTV-TS-ENTRY (20261005) |
| 25395a2 | pinned inspection, sample epochs, seek policy | IPTV-RETAINED-MEDIA (20261005) |
| 4606418 | transactional staging, Media3 metadata | IPTV-SAMPLE-STAGING (20261006) |
| ae0c08b | physical storage fences | IPTV-STORAGE-FENCE (20261006) |
| 6842df1 | finite pinned period | IPTV-PINNED-PERIOD (20261006) |
| a960696 | async exact-seek reader | IPTV-PINNED-READER (20261006) |
| 0b82357 | incremental inspection/TS queue | IPTV-SAMPLE-LOAD (20261006) |
| 34a4937 | governed incremental reader | IPTV-INCREMENTAL-READER (20261006) |
| ed528c6 | growing epoch period/borrow | IPTV-EPOCH-PERIOD (20261006) |
| 6ad3799 | source/callback binding | IPTV-EPOCH-SOURCE (20261006) |
| b68985a | ID3 exclusion, AM9 capture validation | IPTV-EPOCH-DEVICE-VALIDATION (20261006) |

Earlier architecture: eaa43e5 HLS source/HTTP fences; 4c3e0b0 shared capture;
8f1316e store; 0211656 HUD/document guides/secure guide redirects/native tracks.
Earlier ingest/guide/playback reports are the *-20261005.json files in this folder.

## Existing ingest/guide/prototype architecture to preserve

Bounded M3U ingest, source-scoped stable IDs/reconciliation/tombstones, last-good
transactions and shrink review; encrypted Keystore/SQLite credentials/catalogue
(schema3), profile session fences and overlays for favourite/hidden/name/manual
mapping and AUTO/HLS/MPEG_TS. Paged browse24/max200, revision-fenced Unicode search.
Older sources use the account alias shared-default; new sources (and any source
whose connection limit is changed) get their own account `src-<sourceId>`.
Xtream sequential auth/categories/live refresh checks active/expiry and encoded
credential components; no media/logo/direct_source/advertised-host speculative
fetch. TS preferred, advertised HLS fallback. Metadata uses Connection: close
for reproduced HTTP/1.0 stale-pool failure, without automatic retry.

Multiple XMLTV URL/gzip/document feeds, precise timestamps/feed-scoped mapping,
transactional promotion and last-good/cache validators; encrypted content URIs
and persisted grants on Save. Guide redirects are bounded6, same-origin HTTP or
cross-origin HTTPS, no downgrade/userinfo/fragments/cookies/auth/referrer, with
validator isolation. Media redirects remain rejected. tinyurl.com/epg-ss11 was
404 in bounded public HEAD/GET on 5 October; no guide content certified.

LIVE_CHANNEL bypasses VOD probes/cache/prefetch/thumbnails. LivePlaybackRuntime /
LiveRequestFence retain provider/decoder/memory through confirmed closure, fence
late connects/stale replacements/background/profile exits, and never auto-reopen
on foreground return. Unknown capacity: one acquisition/one foreground decoder;
16MiB acquisition +96MiB viewer under192MiB are ESTIMATES, not measured total limits.
Player disables both failed-load retry delay AND alternate HLS rendition fallback
(after a reproduced503 fallback). Explicit HLS handles extensionless URLs; format
choice applies next activation with no probe. Existing foreground HLS/range support
is broader than the capture subset; do not accidentally narrow it.

Prototype has Original/V2 source forms, preview/expanded video, source cycle,
paged channels/favourites and focused programme info. Focus opens no media. HUD
uses existing presentation, main-thread player reads, 1Hz visible sampling,
actual format/buffer/body-rate/request/tune/rebuffer/drop metrics without URLs /
credentials, inferred file-size, speed-test, glass-delay or HDMI-rate claims.
Track dialog supports reported audio/text, Automatic/Off/stale-group checks;
choices reset per player. No general CC/DVB/teletext rendering certification.

## Remaining scope (capture chain; earlier plan)

Items 3 and 4 are partly superseded: recording now uses its own copier and service,
and Stalker, the automatic Xtream guide and per-source connections have UI.

1. Source/Looper, period, reader/queue/stager/storage and normalized codec cases
   now pass on AM9 (see new continuation/report). Next implement actual governed
   ExoPlayer/renderer consumers with one admission and actual shared transport,
   output/preroll discard and exact pending seek/render acknowledgement. Preserve
   reservations through uncertain player release; source close alone is not proof.
2. Measure aggregate transient/parser/object/staging/Java/native/graphics/decoder
   budgets above encoded floor and real physical margins. Validate pause/resume,
   backpressure/expiry, navigation/profile/background exits and confirmed teardown.
   Do not force enabled controls as a workaround for unvalidated ownership.
3. Durable record-now, persisted leases/jobs/schedules, foreground service/process
   death reconciliation, safe cancel/exit/recovery; internal/USB/SMB allocation and
   delivery. Mock recorder survival and atomic spool sync are NOT durable recording
   or physical power-loss evidence. Broaden progressive TS/HLS init/ranges/renditions
   only with supported entry points, ownership and budgets.
4. Stalker Portal, automatic Xtream guide and account groups exist as logic (see
   above); remaining: their UI, feed/source management, mapping/facets/paging and
   rejected-candidate review. Preserve overlays/profile isolation.
5. Different-source multiview, catch-up and aggregate VOD/trailer/IPTV ownership,
   single audio/display owner, provider limits and AFR/display handoff.
6. Full guide grid, Home favourites/sports/recordings, Search, immersive watch-first
   Nuvio Original/V2 UX, source-form IME/Save focus, HUD/replacement/background,
   remote audio/text select/off/render, preferences/locales/accessibility and
   representative subtitle/CC/DVB/teletext/hardware validation.

Do useful independent work around device/provider/UI blockers; do not repeat
finished competitor/upstream research or silently fetch real providers.

## Build and test

Set `JAVA_HOME` (JDK 17+), `ANDROID_HOME` and, for the core runner,
`GRADLE_MODULE_CACHE` (a Gradle `modules-2/files-2.1` cache holding the pinned jars).

```
./gradlew :app:compileFullDebugKotlin
./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.core.iptv.*' --tests 'com.nuvio.tv.data.iptv.*'
./gradlew -p tools/iptv-device-tests assembleDebug assembleDebugAndroidTest
python3 scripts/check_iptv_core.py
```

Low-memory machines can add `--no-daemon --max-workers=1` and a smaller
`-Dorg.gradle.jvmargs` heap. Do not upgrade dependency versions to make a check pass.
Instrumentation steps are in `tools/iptv-device-tests/README.md`.
Without the Android SDK (for example in a cloud session), `python3
tools/iptv-host-tests/run.py` runs the core and data-layer JVM tests and compile-checks
the device tests; see its README. The `PR Full Debug Build` workflow can build the
prototype APK on GitHub Actions (manual run, variant `iptvPrototype`).

## Device test rules

- Use only the dedicated AM9 test device; pass its serial explicitly to every adb call.
- Check its state first. Do not wake the TV or AVR, or change power, CEC, settings,
  accounts or recordings.
- Install only the two validation packages and uninstall them afterwards. Leave the
  installed prototype and comparison apps alone.
- Use synthetic fixtures and local fixture servers only; no provider traffic or
  credential copying. Black HDMI-sleep screenshots are not playback evidence.
- AM9 has no system document picker; the synthetic picker in the test APK covers
  the grant flow only.

## Fixtures

`app/src/test/resources/iptv-ts` holds the synthetic TS segments used on the JVM and
device. `tools/iptv-device-tests/validate_capture_media.py` independently probes and
decodes them; generate new fixtures only into an empty directory.
