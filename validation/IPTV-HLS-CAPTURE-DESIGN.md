# Bounded HLS capture adapter

Development checkpoint, 5 October 2026. This adapter is not enabled in the
foreground player. It does not provide playable timeshift, recording UI or
decoder-safe seeks.

## Supported ingestion

`HlsCapturePlaylistParser` reads strict UTF-8 media playlists within a 256 KiB,
1024-segment default budget. It preserves media/discontinuity sequence and
manifest durations, resolves relative segment addresses and permits only HTTP(S)
resources on the playlist origin, without URL userinfo or fragments. Construction
and failures expose no endpoint/parser excerpts or nested network causes.

The supported profile is one media playlist containing complete resources.
Master/rendition selection, encryption (including key tags), initialization maps,
byte ranges, gaps, I-frame playlists, low-latency partial segments and unknown
EXT tags are rejected before segment fetch. This is a deliberate initial subset,
not certification of general HLS. Cross-origin resources and media redirects
remain unsupported. Independent-segments tags are never treated as decode proof.

`HlsCaptureSegmentSource` starts at the oldest advertised segment of an explicitly
started capture. It requests one complete body, requires its closure before the
next pull, then follows that media sequence. It checks overlapping URI/duration/
discontinuity metadata on reload. Missing expected media, backward windows or
changed overlap halt capture rather than silently creating a gap or splicing
different media. Reload pacing uses target duration after a changed playlist and
half target after an unchanged one; waiting has a bounded stall deadline.
Normal live reloads are separate from retries of failed requests, which are disabled.

`IptvCaptureHttp` uses a dedicated client, no shared player cancellation, redirects,
automatic connection retries, cookies, authenticators or cache. It requests
identity transfer encoding and enforces advertised and streamed byte limits.
Only HTTP 200 is accepted. Response bodies remain accounted for until explicit
closure, including responses lost when cancellation occurs before dispatch back
to the caller. Closing fences new requests, cancels current calls and waits within
a bounded timeout for actual connecting/body owners to finish.

Failed source-level manifest/body closure retains handles. HTTP response-source
closure bypasses OkHttp's quiet Response.close path. A buffered source may mark
itself closed before its underlying close throws; later no-op closure cannot
establish release. Such uncertainty permanently fences that client instance and
retains its reservation. There is no fabricated provider-quota release guarantee.

## What timing means

Capture timestamps are accumulated manifest durations from capture origin zero,
with milliseconds rounded from EXTINF. They are not independently measured PTS,
wall-clock programme times, live latency or safe decoder entry points. Segment
content is streamed without claiming its codec/container was validated. Existing
store bounds exclude declared discontinuities; usable playback bounds still need
PAT/PMT/init, codec, keyframe and timestamp validation against actual media.

## Validation and next work

The final host checks passed 130 pure-core tests and 10 HTTP fixture tests.
The latter use the shipped Android OkHttp AAR with API-0/logging stand-ins to run
real loopback HTTP on the JDK; they do not establish Android networking behavior.
The final Android harness compiled the production sources and passed 190 tests
on AM9 in 35.827 seconds. It has no INTERNET permission; three new HTTP tests use
in-process responses on real Android/OkHttp. Suites overlap.

The loopback integration test fetches a playlist and two small synthetic byte
resources through the actual source/transport/store, then reads committed local
bytes. It is protocol/storage evidence, not playback or TS decoding evidence.
Both temporary Android packages were removed; no prototype reinstall, playback,
provider traffic, power/account preference changes or new UI validation occurred.
The device reported awake during this run; it was not woken by this task.

Next: validate real controlled transport segments and decoder-safe entry points,
then add a local waiting/ended/expired reader and Media3 timeline. Production
capture/player sharing, physical storage margins, durable leases/services,
schedules, USB/SMB, remaining adapters and product UI remain outstanding.

Protocol reference: [RFC 8216](https://www.rfc-editor.org/rfc/rfc8216.html), especially
media/discontinuity sequences and media-playlist reload/overlap rules.
Exact outcomes, build limitations and source hashes are in
`IPTV-HLS-CAPTURE-VALIDATION-20261005.json`.

Full application Kotlin compilation and all 176 IPTV JVM tests also passed.
The first 3 GiB run failed from native allocation and a 2 GiB run exhausted its
useful heap. A 3 GiB run with SerialGC, 512 MiB metaspace, 128 MiB code cache and
two reported processors passed compilation in 6m28s. An unchanged-output run
with those overhead limits and a 1 GiB heap passed the full JVM suite. Project
memory settings and unrelated processes were unchanged.
