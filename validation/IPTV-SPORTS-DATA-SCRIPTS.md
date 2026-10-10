# Sports data sample scripts (PowerShell, run on a PC)

The build environment cannot reach ESPN or TheSportsDB, so samples are collected on the
user's PC and attached to the session. Results of the 8 October 2026 runs are summarised
in [IPTV-SPORTS-DATA-FIELDS.md](IPTV-SPORTS-DATA-FIELDS.md); trimmed copies used by the
parser tests are in `app/src/test/resources/sports/`. Paste each block into PowerShell;
for the key script, type the `$key = "..."` line on its own first (a pasted `Read-Host`
consumes the next pasted line). Never commit a key.

## ESPN capture (best while big games are live)

```powershell
$out = "$env:USERPROFILE\Downloads\espn-pass"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Get-Json($name, $url) {
  try { Invoke-WebRequest -UseBasicParsing -UserAgent "Mozilla/5.0" -Uri $url -OutFile "$out\$name.json"; Write-Host "ok   $name" }
  catch { Write-Host "FAIL $name : $($_.Exception.Message)" }
}
$espn = "https://site.api.espn.com/apis/site/v2/sports"
Get-Json "all-sports-header" "https://site.web.api.espn.com/apis/v2/scoreboard/header?region=au&lang=en&contentorigin=espn"
$leagues = [ordered]@{
  "epl"="soccer/eng.1"; "ucl"="soccer/uefa.champions"; "aleague"="soccer/aus.1"; "nfl"="football/nfl";
  "ncaaf"="football/college-football"; "nba"="basketball/nba"; "nbl"="basketball/nbl"; "nhl"="hockey/nhl";
  "mlb"="baseball/mlb"; "afl"="australian-football/afl"; "nrl"="rugby-league/3"; "rugby-urc"="rugby/270557";
  "atp"="tennis/atp"; "wta"="tennis/wta"; "pga"="golf/pga"; "f1"="racing/f1"; "nascar"="racing/nascar-premier";
  "ufc"="mma/ufc"; "cricket"="cricket/8676" }
foreach ($k in $leagues.Keys) {
  Get-Json "$k-scoreboard" "$espn/$($leagues[$k])/scoreboard"
  $sb = Get-Content "$out\$k-scoreboard.json" -Raw -ErrorAction SilentlyContinue | ConvertFrom-Json
  $live = $sb.events | Where-Object { $_.status.type.state -eq "in" } | Select-Object -First 1
  $ev = if ($live) { $live } else { $sb.events | Select-Object -First 1 }
  if ($ev) { Get-Json "$k-summary$(if ($live) {'-LIVE'})" "$espn/$($leagues[$k])/summary?event=$($ev.id)" }
}
Compress-Archive -Force -Path "$out\*" -DestinationPath "$env:USERPROFILE\Downloads\espn-pass.zip"
```

## TheSportsDB premium coverage (type `$key = "..."` first)

```powershell
$out = "$env:USERPROFILE\Downloads\sports-coverage"
New-Item -ItemType Directory -Force -Path $out | Out-Null
function Get-Json($name, $url, $headers = @{}) {
  try { Invoke-WebRequest -UseBasicParsing -UserAgent "Mozilla/5.0" -Headers $headers -Uri $url -OutFile "$out\$name.json"; Write-Host "ok   $name" }
  catch { Write-Host "FAIL $name : $($_.Exception.Message)" }
}
$h = @{ "X-API-KEY" = $key }
$v2 = "https://www.thesportsdb.com/api/v2/json"
Get-Json "v2-all-sports" "$v2/all/sports" $h
Get-Json "v2-all-leagues" "$v2/all/leagues" $h
Get-Json "v2-livescore-all" "$v2/livescore/all" $h
$all = Get-Content "$out\v2-all-sports.json" -Raw | ConvertFrom-Json
foreach ($n in ($all.all | ForEach-Object { $_.strSport } | Sort-Object -Unique)) {
  Get-Json "v2-livescore-$(($n.ToLower() -replace '[^a-z0-9]', ''))" "$v2/livescore/$($n -replace ' ', '_')" $h
}
Get-Json "v2-search-team-arsenal" "$v2/search/team/arsenal" $h
Get-Json "v2-list-teams-afl" "$v2/list/teams/4456" $h
Get-ChildItem $out -Filter *.json | ForEach-Object { (Get-Content $_.FullName -Raw) -replace [regex]::Escape($key), "KEY" | Set-Content $_.FullName }
Compress-Archive -Force -Path "$out\*" -DestinationPath "$env:USERPROFILE\Downloads\sports-coverage.zip"
```

## TheSportsDB calls the app makes (run after the block above, while games are live)

These replace the remaining synthetic samples (livescore per sport, `lookuptv`, the league
list). Run it in the same window (it reuses `$key`, `$out`, `$v2`, `$h` and `Get-Json`);
the last line removes the key from every file before zipping.

```powershell
$v1 = "https://www.thesportsdb.com/api/v1/json/$key"
foreach ($s in "Soccer","Basketball","Ice_Hockey","Baseball","American_Football","Rugby","Cricket","Australian_Football") {
  Get-Json "v2-livescore-app-$($s.ToLower())" "$v2/livescore/$s" $h
}
Get-Json "v1-all-leagues" "$v1/all_leagues.php"
$day = (Get-Date).ToString("yyyy-MM-dd")
Get-Json "v1-eventsday-epl" "$v1/eventsday.php?d=$day&l=English_Premier_League"
Get-Json "v1-eventsday-afl" "$v1/eventsday.php?d=$day&l=Australian_AFL"
$ids = @()
foreach ($f in Get-ChildItem $out -Filter "v2-livescore-app-*.json") {
  $j = Get-Content $f.FullName -Raw | ConvertFrom-Json
  if ($j.livescore) { $ids += $j.livescore | Select-Object -First 3 | ForEach-Object { $_.idEvent } }
}
$e = Get-Content "$out\v1-eventsday-epl.json" -Raw | ConvertFrom-Json
if ($e.events) { $ids += $e.events | Select-Object -First 3 | ForEach-Object { $_.idEvent } }
foreach ($id in ($ids | Where-Object { $_ } | Select-Object -Unique -First 10)) { Get-Json "v1-lookuptv-$id" "$v1/lookuptv.php?id=$id" }
Get-ChildItem $out -Filter *.json | ForEach-Object { (Get-Content $_.FullName -Raw) -replace [regex]::Escape($key), "KEY" | Set-Content $_.FullName }
Compress-Archive -Force -Path "$out\*" -DestinationPath "$env:USERPROFILE\Downloads\sportsdb-calls.zip"
```

v2 authenticates with the `X-API-KEY` header; v1 puts the key in the path
(`/api/v1/json/<key>/...`, free test key `123`).
