#!/usr/bin/env python3
"""Usage: sports_capture.py <out-dir> [--espn-only]  (TheSportsDB reads THESPORTSDB_KEY)"""
import json, os, re, ssl, sys, urllib.request
from datetime import datetime, timedelta, timezone

ESPN = "https://site.api.espn.com/apis/site/v2/sports"
HEADER = "https://site.web.api.espn.com/apis/v2/scoreboard/header?region=au&lang=en&contentorigin=espn"
LEAGUES = {
    "epl": "soccer/eng.1", "ucl": "soccer/uefa.champions", "aleague": "soccer/aus.1", "nfl": "football/nfl",
    "ncaaf": "football/college-football", "nba": "basketball/nba", "nbl": "basketball/nbl", "nhl": "hockey/nhl",
    "mlb": "baseball/mlb", "afl": "australian-football/afl", "nrl": "rugby-league/3", "rugby-urc": "rugby/270557",
    "atp": "tennis/atp", "wta": "tennis/wta", "pga": "golf/pga", "f1": "racing/f1", "nascar": "racing/nascar-premier",
    "ufc": "mma/ufc", "cricket": "cricket/8676", "cfl": "football/cfl", "mls": "soccer/usa.1",
    "eflcup": "soccer/eng.league_cup", "uel": "soccer/uefa.europa", "ncaah": "hockey/mens-college-hockey",
    "lpga": "golf/lpga", "pfl": "mma/pfl",
}
NO_SUMMARY = {"atp", "wta", "pga", "lpga", "f1", "nascar", "ufc", "pfl"}
V2 = "https://www.thesportsdb.com/api/v2/json"
V1 = "https://www.thesportsdb.com/api/v1/json"
APP_SPORTS = ["Soccer", "Basketball", "Ice_Hockey", "Baseball", "American_Football", "Rugby", "Cricket", "Australian_Football"]
BRISBANE = timezone(timedelta(hours=10))


def context():
    bundle = os.environ.get("SSL_CERT_FILE") or os.environ.get("REQUESTS_CA_BUNDLE")
    if not bundle and os.path.exists("/root/.ccr/ca-bundle.crt"):
        bundle = "/root/.ccr/ca-bundle.crt"
    return ssl.create_default_context(cafile=bundle) if bundle else ssl.create_default_context()


class Fetcher:
    def __init__(self, key):
        self.key = key
        self.ssl = context()

    def scrub(self, text):
        return text.replace(self.key, "KEY") if self.key else text

    def get(self, folder, name, url, headers=None):
        request = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0", **(headers or {})})
        try:
            with urllib.request.urlopen(request, timeout=30, context=self.ssl) as response:
                body = response.read().decode("utf-8", "replace")
        except Exception as error:
            print(f"FAIL {name} : {self.scrub(str(error))}")
            return None
        os.makedirs(folder, exist_ok=True)
        with open(os.path.join(folder, name + ".json"), "w", encoding="utf-8") as out:
            out.write(self.scrub(body))
        print(f"ok   {name}")
        try:
            return json.loads(body)
        except ValueError:
            return None


def espn(fetch, folder):
    fetch.get(folder, "all-sports-header", HEADER)
    for key, path in LEAGUES.items():
        board = fetch.get(folder, f"{key}-scoreboard", f"{ESPN}/{path}/scoreboard")
        if key in NO_SUMMARY or not board:
            continue
        events = board.get("events") or []
        live = next((e for e in events if ((e.get("status") or {}).get("type") or {}).get("state") == "in"), None)
        event = live or (events[0] if events else None)
        if event and event.get("id"):
            fetch.get(folder, f"{key}-summary{'-LIVE' if live else ''}", f"{ESPN}/{path}/summary?event={event['id']}")


def coverage(fetch, folder, key):
    h = {"X-API-KEY": key}
    sports = fetch.get(folder, "v2-all-sports", f"{V2}/all/sports", h)
    fetch.get(folder, "v2-all-leagues", f"{V2}/all/leagues", h)
    fetch.get(folder, "v2-livescore-all", f"{V2}/livescore/all", h)
    names = sorted({s.get("strSport") for s in (sports or {}).get("all") or [] if s.get("strSport")})
    for name in names:
        fetch.get(folder, "v2-livescore-" + re.sub(r"[^a-z0-9]", "", name.lower()), f"{V2}/livescore/{name.replace(' ', '_')}", h)
    fetch.get(folder, "v2-search-team-arsenal", f"{V2}/search/team/arsenal", h)
    fetch.get(folder, "v2-list-teams-afl", f"{V2}/list/teams/4456", h)


def calls(fetch, folder, key):
    h = {"X-API-KEY": key}
    v1 = f"{V1}/{key}"
    ids = []
    for sport in APP_SPORTS:
        data = fetch.get(folder, f"v2-livescore-app-{sport.lower()}", f"{V2}/livescore/{sport}", h)
        ids += [r.get("idEvent") for r in ((data or {}).get("livescore") or [])[:3]]
    fetch.get(folder, "v2-livescore-all", f"{V2}/livescore/all", h)
    fetch.get(folder, "v1-all-leagues", f"{v1}/all_leagues.php")
    today = datetime.now(BRISBANE).date()
    day, prev = today.isoformat(), (today - timedelta(days=1)).isoformat()
    epl = fetch.get(folder, "v1-eventsday-epl", f"{v1}/eventsday.php?d={day}&l=English_Premier_League")
    fetch.get(folder, "v1-eventsday-afl", f"{v1}/eventsday.php?d={day}&l=Australian_AFL")
    fetch.get(folder, "v1-eventsday-mlb", f"{v1}/eventsday.php?d={day}&l=MLB")
    fetch.get(folder, "v1-eventsday-mlb-prev", f"{v1}/eventsday.php?d={prev}&l=MLB")
    ids += [e.get("idEvent") for e in ((epl or {}).get("events") or [])[:3]]
    seen = []
    for event in ids:
        if event and re.fullmatch(r"[0-9]{1,10}", str(event)) and event not in seen:
            seen.append(event)
    for event in seen[:10]:
        fetch.get(folder, f"v1-lookuptv-{event}", f"{v1}/lookuptv.php?id={event}")


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    out = sys.argv[1]
    key = os.environ.get("THESPORTSDB_KEY", "").strip()
    fetch = Fetcher(key)
    espn(fetch, os.path.join(out, "espn-pass"))
    if "--espn-only" in sys.argv:
        return
    if not key:
        print("FAIL thesportsdb : THESPORTSDB_KEY is not set")
        return
    coverage(fetch, os.path.join(out, "sports-coverage"), key)
    calls(fetch, os.path.join(out, "sportsdb-calls"), key)
    leaked = [os.path.join(d, f) for d, _, files in os.walk(out) for f in files if key in open(os.path.join(d, f), encoding="utf-8", errors="replace").read()]
    if leaked:
        for path in leaked:
            os.remove(path)
        print(f"FAIL scrub : removed {len(leaked)} files that still held the key")


if __name__ == "__main__":
    main()
