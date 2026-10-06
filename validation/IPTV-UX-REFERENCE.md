# Nuvio IPTV — reference app study (Janky 0.95.69 beta)

Studied 6 October 2026 as design input. Janky is closed source with no licence; its
GitHub repository only publishes APKs. The APK was decompiled to understand behaviour
and approach. Nothing from it (code, HTML, text or assets) may be copied into Nuvio;
everything below is a description of ideas, with our own design decisions.

## Summary

Janky is a Nuvio-like Android TV media app (Emby, Jellyfin, Plex, Trakt, Simkl, debrid
addons) with IPTV built in: Views/Leanback UI, Room, WorkManager, Hilt, Media3 with
FFmpeg fallback renderers. Its IPTV strengths are the guide-first home, a fast
row-based guide, background refresh, guide matching, phone/PC setup and sports
matching. It has no local timeshift, no recording, no provider connection-limit
handling, and weak security in several places.

## Ideas to adopt

### Refresh and import
- Background refresh as scheduled work, independent of the open screen: channels about
  every 12 h, guides about every 6 h, a few retries, plus a one-off refresh when the
  guide is opened and its data is older than 6 h. Per-source "last refreshed".
- Visible, non-blocking progress ("Parsed N channels — saving…", "Refreshing guide")
  shown as a small status pill rather than a blocking spinner.
- Guide downloads kept on disk (compressed) with a content hash; unchanged guides and
  304 responses skip re-import; a parser version prefix forces re-import after a parser
  change.
- Guide import as a producer/consumer pipeline: parser emits batches (thousands of
  programmes), a writer inserts with multi-row statements inside one transaction.
- For a provider's own guide, keep only programmes for channels present in the
  playlist. This is what makes 100+ MB provider guides practical.
- Guide window roughly a week back to a week ahead; old rows pruned on each import.
- Large channel lists written in chunks with per-chunk progress.
- Full-text index for channel search; "now airing" search over current programme
  titles.

### Guide matching
- Per-service ordered list of guide sources, with the service's own guide as one
  entry that can be switched on or off; earlier sources win.
- Manual override per channel ("pick a guide, then a channel") with a searchable list.
- Automatic name matching for unmatched channels: strip short country prefixes
  ("UK:", "US |"), punctuation, emoji and trailing quality tags (HD, FHD, 4K, HEVC,
  60fps…), then bind only when exactly one guide channel matches. No fuzzy matching.
- An M3U's guide address registered automatically when present.

### Live TV screen
- The guide is the Live TV home. It opens on the last channel, already previewing.
- Layout: live preview window and programme info panel above a channel/time grid.
- Focus is on the channel row, not on individual cells. Left/Right moves one shared
  time cursor to the previous or next programme across all rows; Up/Down changes
  channel and wraps. Fast and predictable with a remote; avoids per-cell focus cost.
- One shared horizontal offset for all rows, time bar and now line, so rows bound
  while scrolling are already aligned. Time bar and now line drawn, not laid out.
- Fixed time scale (about 6 dp per minute, roughly 2 h on screen); two densities
  (compact and comfortable, about 20% larger rows and text).
- Programme cells: airing-now tint, accent fill when selected, sticky title when a
  cell is partly off-screen, optional channel name in empty slots.
- Info panel updates after a short debounce (about 250 ms); artwork looked up and
  cached per title.
- Category rail on the left (favourites first, then categories grouped by service,
  collapsible), hidden while browsing the grid; Back reveals rail, then navigation.
- OK previews a channel in the window; OK again (or holding Back) goes full screen
  with the same player, so there is no re-tune.
- Long-press menu: favourite, reorder channels/favourites, assign guide, catch-up.
- Reorder mode with clear key hints (move, send to top/bottom).
- Category management: hide/show per service, reorder services, active/inactive.

### Player overlays
- Info overlay: logo, programme, progress, "ends around", quality badges (resolution,
  HDR, codec, audio format, frame rate, bitrate), refresh, start from beginning.
- Last-channel recall on a single key.
- Side panel over live video: channel list with now-playing, plus the focused
  channel's schedule (from two hours back to a day ahead).
- Recent-channels row.

### Playback
- One long-lived player and view reused between guide preview and full screen.
- Small start thresholds (under a second to start, about 1.5 s after a stall), no back
  buffer for live.
- HLS live: target offset a few seconds behind the edge with ±3% speed adjustment.
- Live errors retried indefinitely with short backoff (1, 2, 3, then 5 s), reset on
  successful playback.
- When a stream's format is not recognised, one request to inspect the final address
  and content type, then replay with the right source type.
- Diagnostics: decoder start time, first frame, buffering headroom and network rate.

### Catch-up
- One address builder for Xtream archives and the common M3U catch-up types
  (template, append, shift, Flussonic), with provider time offset and correction.
- Catch-up bar: start from beginning, scrub across programme boundaries, continue into
  the next programme, return to live automatically when within about 10 s.

### Phone/PC setup
- While a setup screen is open, the TV serves a local web page and shows a QR code
  and address; clear messages when there is no network.
- Pages for services (Xtream/M3U switch on one form), guides, guide assignment,
  profiles and app settings; clear success and "nothing was saved" error pages;
  secret fields left blank to keep the stored value; dangerous actions separated and
  confirmed.
- Device-to-device copy of the whole setup over the local network.

### Sports
- Fixtures from a sports data feed matched to channels two ways: event channels whose
  names contain both teams, and programmes airing now whose titles contain both
  teams; nickname aliases; betting, preview and highlight programmes excluded;
  re-matching skipped when the channel list has not changed; the user's chosen
  channel per fixture remembered. Shown as a Sports category in the guide.

### Multiview
- Up to four players with layouts (single, side by side, one-over-two, 2×2); audio
  follows focus; a slot can go full screen without re-tuning; picture-in-picture with
  a quick swap of main and inset.

## Weaknesses to avoid

- Xtream logins stored in every channel row and written to logs; guide address built
  without encoding the login; secrets silently stored unencrypted when the Keystore
  fails.
- Setup web page protected only by a 4-character path code, no rate limit, no CSRF
  or origin checks, plain HTTP, passwords echoed back into edit forms, profile PINs
  changeable without the current PIN, server bound to all interfaces with no idle
  timeout.
- Device sync sends its PIN in the URL over plain HTTP and uses the same PIN as the
  encryption key; it replaces the target's setup wholesale.
- Live playback stops for good when it falls behind the live window.
- No awareness of provider connection limits; multiview and probes open extra
  connections unchecked; HLS requests skip custom headers.
- No size limits on playlists or guides; M3U `url-tvg` and per-stream header options
  ignored.
- No number entry or channel up/down keys for zapping.

## Where Nuvio is already ahead

Encrypted credentials kept out of channel rows, bounded parsing, safe redirects,
connection admission and confirmed teardown, explicit stream format choice, and the
local capture chain that local timeshift and recording will build on.

## Recommended order for Nuvio

1. Background refresh service with staged progress, last-refreshed times and
   hash/304 skipping; provider guides filtered to known channels.
2. Guide-first Live TV redesign: preview and info panel, row-focus guide grid with a
   shared time cursor, category rail, long-press menu, densities, shared player
   between preview and full screen.
3. Guide matching: source priority per service, normalised unique-name auto-match,
   manual "pick a guide, then a channel".
4. Player overlays: info and quality badges, last channel, side panel with schedule,
   recent channels, number entry and channel keys.
5. Phone/PC setup with a pairing code exchanged for a session, CSRF and origin checks,
   LAN-only binding, idle timeout and no secrets echoed.
6. Live playback hardening: behind-live-window recovery, endless live retry with
   backoff, HLS live speed adjustment, format probe; check against Nuvio's player.
7. Catch-up builder and bar; search with full-text index and "now airing".
8. Sports matching, then multiview and picture-in-picture within decoder admission.
