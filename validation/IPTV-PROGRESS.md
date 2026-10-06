# Nuvio IPTV — progress

Current status and next steps: [IPTV-HANDOVER.md](IPTV-HANDOVER.md).
Updated 6 October 2026.

| Date | Commits | Work | Validation |
| --- | --- | --- | --- |
| 5 Oct | 196dcf4 – a9871c7 | Ingest and admission, catalogue, XMLTV, browse, source forms, governed live playback, Xtream, stream format choice | Host JVM and AM9 fixtures; controlled TS/HLS playback on AM9 |
| 5 Oct | 0211656 | HUD, document guides, secure guide redirects, track dialog | 112 JVM, 133 AM9 fixtures |
| 5 Oct | 8f1316e – eaa43e5 | Capture spool, shared capture ownership, HLS capture | 176 JVM, 190 AM9 backend tests |
| 5 Oct | 43b1dff – 25395a2 | TS inspection and local extraction, sample epochs, seek policy | 150 core, 214 AM9 tests; FFmpeg cross-check |
| 6 Oct | 4606418 – 6ad3799 | Staging, storage fences, finite and growing periods, readers, media source | Full compile, 317 IPTV JVM tests |
| 6 Oct | b68985a | Synthetic ID3 track excluded; AM9 capture-path validation | 27 AM9 fixtures incl. normalised codec decode |
| 6 Oct | iptv/player-binding | Video player binding; played-batch retirement (player timeout cause); 26 review fixes; `main` merged; IPTV flavour gate | 222 core + 117 data-layer host tests; device rerun pending |
| 6 Oct | iptv/player-binding | Guide grid model, automatic Xtream guide, account groups and source order, Stalker Portal, local guide folder import (logic only) | 238 core + 121 data-layer host tests |
| 6 Oct | def2b19 | Host test runner; CI builds the prototype APK | First full Gradle compile and IPTV JVM run since b68985a pass on GitHub Actions; device pending |
| 6 Oct | 1c9b744 – 230b425 | First real-account findings fixed (errors, form, envelope sealing, credential-free Xtream rows, background provider guide); background refresh; guide-first Live TV; guide name matching and picker; full-screen banner, channel panel, last channel, number entry | 241 core + 125 data-layer host tests; CI green to 230b425 (run 37456242102); device pending |
| 6 Oct | f4e2c66 – 105791e | Chunked catalogue save, remove sources/guides, logos, feed-suffix matching, guide caps and format fixes, header guides, short guide; catch-up addresses; Live TV and Sources redesign in the fork's design language, player control deck and stats HUD in Live TV, catch-up playback, channel search | 276 core + 137 data-layer host tests; CI green at 105791e (run 37467974960); device pending |
| 6 Oct | 60074ea – f435994 | Landing category, hidden categories, favourites order; review fixes (full screen on zap, focus, Back, catch-up seeking, background reload rows, Sources actions, short guide refetch); number entry beyond loaded pages; sticky removal of automatic guides | 276 core + 137 data-layer host tests; CI green at f435994 (run 37474346067); device pending |

Design notes and per-checkpoint reports are in this folder
(`IPTV-*-DESIGN.md`, `IPTV-*-VALIDATION-*.json`). Review findings and their status:
[IPTV-CODE-REVIEW-20261006.md](IPTV-CODE-REVIEW-20261006.md).
