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

Design notes and per-checkpoint reports are in this folder
(`IPTV-*-DESIGN.md`, `IPTV-*-VALIDATION-*.json`). Review findings and their status:
[IPTV-CODE-REVIEW-20261006.md](IPTV-CODE-REVIEW-20261006.md).
