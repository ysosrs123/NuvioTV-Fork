# Nuvio IPTV — reference app notes (Janky 0.95.69 beta)

Reviewed 6 October 2026 as design input only. The GitHub repository `jankyapp/jankyapp`
publishes APKs with no source code and no licence, so nothing may be copied. Notes come
from the APK's packaging (bundled libraries) and its visible interface text; its code
was not decompiled.

## What it is

A general Android TV media app (Emby, Jellyfin, Plex, Trakt, Simkl, MDBList, debrid
addons) with IPTV built in, much like Nuvio. Views/Leanback UI, Room with Paging,
WorkManager, Hilt, Media3 with an FFmpeg decoder extension.

## Ideas worth adopting

Setup and management
- Phone or PC setup: the TV serves a local web page for adding IPTV services, EPG
  feeds, media servers and profiles, gated by an admin PIN. Avoids typing URLs and
  passwords with a remote. Fits Nuvio's existing phone-pairing flows.
- A service is one Xtream or M3U entry with name, URL, credentials, optional EPG URL,
  and include-live / include-VOD switches.
- EPG feeds are managed separately, assigned to services with a priority order; the
  service's own guide (Xtream `xmltv.php` or the M3U `url-tvg`) is a "bundled default
  EPG" toggle; at least one EPG must stay active; assigning with no feeds prompts to
  add one first.
- Per-service adult-category switch; kids profiles force it off.
- Reorder channels and favourites; custom channel names.

Guide and browsing
- Full EPG grid with a guide-density setting, 12-hour clock option and channel name
  shown in empty guide slots.
- Manual mapping flow: "pick a guide, then a channel".
- Catch-up navigation from the guide; record; multiview slots.
- "Live from your services" and "Now airing" rows on Home.
- Sports: fixtures matched to channels through EPG keys, with a sports home and
  "now airing" matches.

Performance and feedback
- Channels and programmes in a database read through paging; background refresh
  through WorkManager with a "last refreshed" time, independent of the open screen.
- Visible import stages ("Finding channels", "Importing channels", "Refreshing EPG").
- Codec warming before tune for faster channel changes; source frame-rate matching
  toggle.

## How this maps to Nuvio IPTV

| Idea | Nuvio status | Note |
| --- | --- | --- |
| Phone/PC setup page | Not started | Highest value for TV text entry; LAN-only, PIN or pairing code, short-lived session |
| Background refresh with progress and last-refreshed | Partial | Refresh runs in the screen's scope and stops on exit; move to WorkManager with stages |
| EPG priorities and bundled guide toggle | Logic exists (feed priority, automatic Xtream guide) | Needs UI |
| Manual EPG mapping | Logic exists (manual mapping overlay) | Needs UI |
| Guide grid with density | Layout model exists (`GuideGrid`) | Needs screen |
| Reorder, custom names, adult switch | Overlays exist for names/hidden/favourites | Ordering and adult switch need work |
| Home "now airing" and sports rows | Not started | Depends on guide import at scale |
| Codec warming for zapping | Not started | Must respect the single-decoder admission limit |
| Multiview, catch-up, record | Below UI or not started | After capture device gates |
