
## About

This is a **personal fork** of [NuvioTV](https://github.com/NuvioMedia/NuvioTV) for test builds,
fixes, modifications, and optimisation. **It isn't for general use, and no support is provided.**

The focus is **speed, efficiency, and optimised playback of high-bitrate 4K remux video and
lossless bitstream audio** -- the kind of content (100 GB+ remuxes, TrueHD/DTS-HD MA/Atmos/DTS:X
passthrough, Dolby Vision) that stresses the parts of a player most builds don't push hard.

**Will likely not work well for Anime, or on low-end devices. Features that write to cache will add wear to internal storage**

NuvioTV itself is a modern, TV-first media player for Android TV, written in Kotlin. It acts as a
client-side playback interface that integrates with the Stremio addon ecosystem for content
discovery and source resolution through user-installed extensions.

**Please support the official project -- [NuvioMedia on GitHub](https://github.com/NuvioMedia).**
This fork is a set of targeted optimisations layered on top of their work, not a replacement for it.

---

## What this fork adds

Optimisation and playback-quality work, most of it aimed at high-bitrate remux and lossless audio.
The short version: streams start faster, buffer deeper, stall less, and you can *see* that your
device is genuinely delivering lossless audio and the right HDR -- without needing to be the kind
of person who tunes buffer settings for fun.

- **Native Dolby Vision Profile 7, FEL and MEL** -- the full dual-layer signal decoded inside
  Nuvio on Android, enhancement layer included, on a supported Amlogic box with the custom kernel
  and a Dolby Vision display. Everywhere else the mode quietly behaves like Auto.
- **Seek thumbnails made by the app itself** -- hold left or right and a strip of pictures shows
  exactly where you will land, from an old AVI to a 4K Dolby Vision remux. Made on your box from
  the film you are watching: no third-party service, no API key, nothing uploaded. Off by default.
- **Watch party** -- watch the same title with friends in other homes, in sync, with a six-character
  code or a QR invite. No account and no server of ours; bitstream audio is never sped up or slowed
  down to catch up.
- **Your own media server** -- Jellyfin, Emby and Silo libraries on Home, in Search, in the Library
  and as a source on any title the server has, with direct play, second addresses and self-signed
  certificates.
- **A surround engine that packs lossless audio itself** -- TrueHD, DTS-HD MA and DTS:X packed for
  HDMI by the app, with an **Auto surround** mode that picks the formats from what your TV and
  receiver report.
- **Player buttons you can arrange** -- show, hide and reorder the player controls per profile.
- **The whole fork in 37 languages.**
- **Lighter, faster build** -- unused engine components stripped out, so the app itself is smaller
  and snappier (see *What's been removed*).
- **Device settings assessment -- your settings, tuned for *your* hardware in one press.** Stop
  guessing what connection count, buffer size or Dolby Vision mode your box wants: the assessment
  measures your link against the last thing you actually played, reads what your device and
  display genuinely support, and lays out recommended settings with the reasoning next to every
  one -- what was measured, what was calculated from your hardware, what's a personal trade-off
  (pick a buffer profile; it'll suggest one based on how steady your connection looked), and what
  it can't honestly know so won't touch. Like the answers? Apply them all in one press. Change
  your mind? Revert restores every previous value, even after a restart. You stay in charge; it
  just does the homework.
- **MDBList watch tracking, alongside Trakt and Simkl** -- sign in on the TV with a code or QR,
  like Trakt, and scrobbling, Continue Watching, resume, watched ticks and your MDBList lists go
  through your account. An API key is only needed for ratings.
- **Audio that fits your gear** -- per-format passthrough switches (Dolby Digital, DD+, TrueHD,
  DTS, DTS-HD) phrased as questions about your receiver, following Kodi's audio settings. Android
  treats passthrough as all-or-nothing, which is no help when your receiver handles every Dolby
  format but no DTS, or takes DTS core but not DTS-HD. Switch off what your gear can't decode and
  Nuvio decodes just those in the app -- with its own decoder, because some boxes ship a vendor
  DTS decoder that quietly folds 5.1 to stereo. A diagnostics row shows what your chain actually
  claims it can take, so you can tell a lying EDID from a real limitation.
- **Fewer stalls, less wasted bandwidth** -- upstream's parallel downloading kept too little data
  queued ahead of playback and threw away chunks it had already paid to download (measured ~47% of
  transfer wasted on a 4K remux). This fork keeps the pipeline properly fed and stops the
  re-downloading, so the buffer builds close to the speed your source can deliver, holds through
  bitrate peaks, and roughly halves the debrid data burned per title. If a debrid CDN rate-limits
  you mid-film, speeds now recover on their own instead of staying throttled to the credits. Plus
  off-heap buffers and a seek fix that makes non-faststart MP4s actually watchable.
- **A speed test that tells you what to do about it** -- not a generic number from a test server:
  an adaptive sweep of connection and chunk-size combinations against the *actual* stream you last
  played, over the same path playback uses. It finds the cheapest configuration that comfortably
  sustains the title and says so in plain terms -- and on memory-tight boxes it's smart enough to
  prefer a config that leaves room for a deeper playback buffer, telling you the trade in MB and
  seconds. It also watches how steady your connection held during the test, which feeds the
  assessment's buffer suggestion. Slow source or buffering bug? Now you can tell them apart.
- **Better streams picked for you, automatically** -- source filtering and sorting re-baselined
  around TRaSH-guides-aligned release-group quality tiers, so the best-quality trustworthy release
  floats to the top and auto-play grabs the right one without you reading twenty filenames. Safe
  to experiment: one tap resets to the recommended baseline, another shows everything unfiltered
  while keeping best-first ordering, and the auto-play settings link straight to the quality rules
  that drive them.
- **Dolby Vision that just works** -- app-side Profile 7 and Profile 5 -> 8.1 conversion via
  libdovi with no per-frame stutter, correct enhancement-layer handling for single-track remuxes,
  and fixes for cases that silently produced wrong or static output before. DV titles look the way
  they're meant to on hardware that was never sold as supporting them.
- **Lossless audio you can trust** -- a hardened bitstream passthrough stack (TrueHD, DTS-HD MA,
  Atmos, DTS:X) that resists mid-playback renegotiation dropping you to lossy without telling
  you, format detection that works on native-DV boxes, a hi-res AC-3 transcode fix, and automatic
  selection of the lossless track so you don't start every film in the wrong audio.
- **Judder-free frame rates** -- precise 23.976-vs-24 matching so films play at their native
  cadence, seamless-switch detection, and a settle-then-resume that stops eARC/soundbar chains
  dropping audio while HDMI renegotiates.
- **MP4s that seek properly** -- non-faststart / poorly-interleaved MP4s no longer thrash and
  stall every time you skip around.
- **Faster stream start** -- pressing play opens the network connection at the press and no longer
  over-fetches the file's tail index. With **Search sources before Play** on (off by default) the
  app also searches and ranks sources while you browse; links are only opened when you press
  play. Some add-ons act on a search, which is why it is your choice.
- **A dead link is not a dead end** -- when a source fails at the start, the player moves on to the
  next ready one by itself, on ExoPlayer and MPV.
- **Old formats that play properly** -- AVI, XviD, DivX, VC-1 and WMV, with frame rate matching.
- **Smoother browsing** -- home-grid scroll-jank reduction and poster prefetch/pre-decode, so the
  UI keeps up with your remote.
- **Stats-for-nerds overlay -- proof, not vibes.** A live diagnostics HUD built for
  high-bitrate/lossless content: measured video bitrate, the audio codec by name with its measured
  passthrough bitrate, whether bitstream is *genuinely* reaching your AVR, HDR/Dolby Vision
  detection, live network throughput and buffer health, the negotiated audio path, SoC stats to know when your SoC is being throtled, audio-clock jitter and underrun cross-checks. When it says
  TrueHD Atmos is hitting the sink, it measured it. The assessment configures; this overlay
  verifies.

## Screenshots (Nuvio V2)

<!-- Paste each image link on the line under its caption (drag the image into GitHub's editor to get the link). -->

**Native Dolby Vision Profile 7 FEL on the Ugoos AM9 Pro** -- the stats overlay showing "Profile 7.6 FEL - RPU + BL + EL" on the native route.

<!-- SCREENSHOT 1: FEL test clip with the stats overlay (Source rows cropped or blanked) -->

**Seek thumbnails** -- the strip and the framed picture while holding left or right.

<!-- SCREENSHOT 2: thumbnail strip during a held seek -->

**Watch party** -- the party page with the code, the phone QR codes and the member list.

<!-- SCREENSHOT 3: Watch party page, in a party -->

**Your own media server** -- Jellyfin, Emby and Silo rows on Home, and a server stream in the source list.

<!-- SCREENSHOT 4: Home with a "Server - Library" row -->
<!-- SCREENSHOT 5: stream list with a Jellyfin / Emby source card -->

**Player buttons** -- the editor with its live preview.

<!-- SCREENSHOT 6: Settings > Player buttons -->

**Surround sound** -- Surround Format on Auto, with the per-format switches.

<!-- SCREENSHOT 7: Settings > Playback > Audio, Surround Sound group (replaces the old per-format switches picture) -->

## Stats for Nerds Overlay -

<img width="1920" height="1080" alt="IMG_3835" src="https://github.com/user-attachments/assets/623b334f-01fd-464f-a49a-c58eb5548e59" />

## MDBList Tracking Integration -

<!-- REPLACE: this picture shows the old API key screen; new one: Settings > Tracking > MDBList, signed in -->

<img width="1920" height="1080" alt="screenshot2" src="https://github.com/user-attachments/assets/808ff170-cac4-4d0e-9f68-412e761748bc" />

## Revised Last Played Stream Speed Test:

<img width="1920" height="1080" alt="screenshot1" src="https://github.com/user-attachments/assets/1ccb4258-7487-4424-82cb-496a776e3c4f" />

## Device Assessment:

<img width="1920" height="1080" alt="screenshot4" src="https://github.com/user-attachments/assets/eb274129-553c-4b5c-b1ac-665aca9101fa" />

## Per-Format Audio Passthrough Switches:

<!-- REPLACE or remove: superseded by SCREENSHOT 7 above -->

<img width="1920" height="1080" alt="Screenshot_20260826_084841" src="https://github.com/user-attachments/assets/85f3a839-11bb-45f6-9154-3d471efe2319" />

---

## What's been removed

- **Home layout picker** -- the app is now **Modern-layout only**. The picker was removed from both
  first-run setup and Settings. The Modern-specific options (landscape posters, full-screen hero
  backdrop, poster/card styling) remain.
- **IAMF** and **MPEG-H** audio decoder components, and an unused UI component -- dropped to slim
  the build. No user-facing loss.
- **Android TV channel / preview-program sync** -- not used on this fork's target setup.
- **The upstream update feed** -- in-app updates come from this fork's own releases only.

**Not removed** (sometimes assumed otherwise): all **40 languages** (the fork's own text is translated into 37 of them), the **add-on
manager**, and **subtitle add-on support** are intact. Only the layout picker was removed.

---

## Target hardware

Primary/validated target is the **Amlogic S905X4 / 4GB RAM, Amlogic S905X5M / 2GB RAM class (armeabi-v7a) and AmLogic S905X5-J / 4GB RAM (armeabi-v8a)**, tested on a Homatics Box R
4K Plus, Xiaomi Box S 3rd Gen, Fire TV Stick 4K Max and Ugoos AM9 Pro in a Samsung HW-Q800F -> LG C9 eARC chain. Other devices (Nvidia Shield, Prism+, Xiaomi) have been
community-tested.

---

## Licence

This is a modified version of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), changed by ysosrs123
from July 2026 onwards. Every change is in this repository's history on top of the official release
it builds on (currently 1.1.0-beta.4). It is licensed under the
[GNU General Public License v3.0](./LICENSE), the same as NuvioTV. All upstream copyright notices,
licence headers and author attributions are kept. Full credit to the NuvioTV maintainers and contributors.

Parts from other projects keep their own licences:
- TrueHD MAT packing and the downmix path: ported from Kodi (GPL-2.0-or-later), see
  `ffmpeg-decoder-downmix/NOTICE.md`.
- Audio passthrough engine: official pull request 3107 by halibiram, with ram130. Media servers:
  official pull request 3728 by tapframe.
- FFmpeg (LGPL-2.1-or-later): audio decoder built from FFmpeg 7.0.2, recipe in
  `.github/workflows/build-ffmpeg-decoder.yml`; headers only under `app/src/main/cpp/third_party/`.
- libdovi by quietvoid (MIT). Matroska extractor files from AndroidX Media3 (Apache-2.0), changed.
- Release group lists from TRaSH Guides (MIT).
- Fonts Atkinson Hyperlegible Next and Source Sans 3 (SIL Open Font License 1.1, texts in
  `app/src/main/assets/licenses/`).
- Watch party avatars: made with DiceBear and Twemoji, under CC BY 4.0, CC0 1.0 and the Bottts terms.
  They are credited in the app under Settings > About > Licenses & attribution and are not covered by the GPL.
- Other service logos and names belong to their owners and only show which service the app connects to.
- Prebuilt libraries that come unchanged from the official app are covered by the official project's
  sources and notices.

This fork is not affiliated with or endorsed by the NuvioTV project or any of the services named.
