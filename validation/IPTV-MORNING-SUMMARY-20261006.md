# IPTV overnight checkpoint summary — 6 October 2026

Latest validated work at 07:04 Australia/Brisbane, branch codex/iptv in the
NuvioTV-IPTV sibling. The separate NuvioTV-Fork checkout was not edited.

- Added immutable inspected/pinned segment evidence, stable actual-PTS/audio
  epochs and both-direction pending seek / explicit captured-tail policies.
- Added transactional compressed-sample staging, Media3 timeline metadata and
  finite pinned MediaPeriod/SampleStream delivery with initial-IDR/audio preroll.
- Added fresh physical storage/unit/volume fences and governed capture admission;
  failure preserves last-good media and existing consumer reservations.
- Added an asynchronous actual owned reader consumer: stale/cancelled seeks cannot
  publish, failed/timed-out cleanup retains handles/pins, and confirmed closure
  preserves an independent recorder through the shared runtime.
- Added incremental inspected-sample/TS loading as capture grows, explicit waiting /
  completion/stopped/expiry/epoch outcomes, worst-case batch/input caps and callback
  reentry fences. Failed staging/cleanup retains pins and arrays until confirmed close.
- Added the governed incremental owned reader: coalesced atomic capture hints,
  one IO worker, cached immutable timelines/batches and immediate snapshot/release
  fences. Failed cleanup retains ownership; an encoded-memory floor is enforced
  before upstream or consumer start. Aggregate decoder budgets remain unmeasured.

- Added actual growing epoch sample delivery with an exclusive reader borrow,
  live WAITING rather than finite EOS, retained cursors/preroll and explicit
  consumed-prefix refill. Period ownership prevents confirmed reader/runtime
  closure; loading callbacks cannot start local tail polling.

- Added actual owned MediaSource and coalesced playback callback binding, with
  unresolved initial live tails and confirmed source/period/callback/reader
  cleanup gates. Period preparation covers queued metadata races and clears own
  format/init references on closure.

Latest validation: full app compile 451s; 317 IPTV JVM tests in 38 suites with zero
failures/errors/skips (8.747s test / 381s build); headless harness builds 53s. The
207 unchanged core cases overlap this JVM run. Exact evidence/hashes are in
IPTV-EPOCH-SOURCE-VALIDATION-20261006.json and prior milestone reports.

A targeted read-only AM9 query reports Asleep. No install, instrumentation,
wake/power/CEC/account/settings/recording/provider operation or upgrade occurred.
New actual Source/Looper/stager/period Android fixtures compile only; source-owner
host tests exercise startup/cleanup before Looper preparation. Earlier bridge
raw-PTS decode results do not certify normalized negative audio, source callbacks
or current admitted player integration. Capture controls remain disabled.

Next: execute the actual Source/Looper and normalized period/offset/preroll fixtures
when authorized AM9 is awake, then bind admitted real player/renderer owners;
actual admitted player/renderer ownership, device offsets/preroll/seek confirmation
and measured aggregate budgets. Durable pause/timeshift/recording/schedules/storage,
Stalker/automatic Xtream EPG/grouping and remaining multiview/guide/Home/Search/UX
scope are still unfinished. Keep controls disabled until their gates pass.

Temporary overnight continuation remains active for further bounded work, with
checkpoint/summary and explicit automation pause due by 08:00 Brisbane. Both
older automations remain paused. Read the comprehensive fresh-session handoff for
current branch, commands, evidence, remaining work and device restrictions.
