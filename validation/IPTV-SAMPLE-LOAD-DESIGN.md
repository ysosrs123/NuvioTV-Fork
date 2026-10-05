# Incremental inspected-sample loading and bounded staging

Internal continuation from a960696, 6 October 2026. Controls remain disabled.
This adds actual incremental local inspection/staging, not an enabled player,
MediaSource, epoch MediaPeriod or background recorder.

CaptureSampleLoadCursor lazily loads one exact sequence from the committed store.
Construction opens no media/pin; it validates metadata bounds. Poll samples producer
state BEFORE the atomic store snapshot/pin so COMPLETE cannot hide a final publication.
NEW/RUNNING with no exact successor means WAITING, never EOF. COMPLETE yields ENDED
only after committed rows drain. FAILED/BACKPRESSURE/STORAGE_BLOCKED/CLOSED yield
STOPPED. Missing older rows yield EXPIRED; no start or successor is substituted.
Terminal outcomes are sticky. There is no automatic network/polling/retry or jump.

A row is pinned before inspection. Exact index/proof/window ownership, fresh input
and cancellation are checked before READY. Actual inspected video/audio clocks and
the existing timeline choose epochs; capture/PTS/init/gap changes are explicit
DISCONTINUITY and never cross automatically. Windows already published in the same
timeline reuse identity. This does not recreate pruned older timeline metadata or
rebase an existing timeline; an unsupported rewind fails without publishing samples.

READY tickets are borrowed, bounded (default two, maximum sixteen) and remain owned
by the cursor. Repeated poll before completion returns the same ticket. complete
requires verified EOF/open input and permits only the exact next sequence; it is
staging/byte completion, not decoder safety or player seek/render acknowledgement.
Completed inputs remain pinned/charged until explicit release. A separate anchor
retains a waiting/terminal tail even when its ticket releases. Successor anchor and
input pins are acquired before old anchor release; old unreleased tickets still
retain their files. Capacity blocks loading rather than dropping any input.

Inspection/cancellation/ownership failure retains candidate handles/pins until
explicit close. Failed ticket release keeps its handle and capacity. Close fences
later operations first, releases owned inputs/candidates/anchor and confirms CLOSED
only when all close calls return; a later explicit close retries remaining handles.
No terminal state or EOF releases ownership. Callback reentry cannot poll, complete,
release or close an in-flight cursor; the queue also fences reentrant load/release/
close before state changes. Regression fixtures protect caps and active IO from
those mutations. Blocking local IO is serialized by
this cursor; a future governed asynchronous owner must cancel/join its sole worker
and run close off the playback thread, retaining reservations through uncertainty.

CaptureSampleBatchQueue connects these tickets to the unchanged actual transactional
LocalCaptureSampleStager. Before any new load, it checks slot capacity and reserves
the next WORST-CASE encoded batch within its logical resident-byte cap. It never
assumes a future batch will be small. Successful staging is kept before cancellation
checks, then the cursor completes and a borrowed batch becomes visible. Failed or
cancelled staging publishes nothing and is sticky, with candidate arrays/pins kept
for closure. Batch release stops charging bytes only after input closure confirms;
failed queue close retains all arrays until a successful explicit retry. The tail
anchor still retains disk after batch release. Resident bytes are encoded/init
charges, NOT measured Java/parser/transient/object/native/graphics/codec memory.
Caller admission must account for every resident batch and all those other costs.

The queue preserves WAITING, CAPACITY, ENDED, STOPPED, EXPIRED, DISCONTINUITY, FAILED
and CANCELLED as distinct outcomes. Retained staged batches can feed the existing
Media3 metadata factory, but this is not a dynamic source/period loader. Do not wire
finite-period EOS to a running capture. Explicit epoch navigation, callbacks/refresh,
actual sample streams across rows, renderer resets/offsets/preroll and seek/render
acknowledgement remain implementation/device gates. Stop/confirm any decoder borrower
before releasing input/batch ownership; this component never confirms decoder closure.

Thirteen core fixtures inspect real original TS files and exercise growth, actual PTS
boundaries, completion ordering, caps, expiry, cancellation, foreign ownership and
retryable close. Seven JVM queue fixtures use SYNTHETIC compressed batches on real
verified pins; they are not Android extraction or decode evidence. Three Android
fixtures use the actual stager across growing rows plus metadata, explicit epoch
boundary and cancellation. They remain unexecuted while device validation is
deferred after the prior Asleep observation. No device/provider operation, component
upgrade or control enablement is needed for host checks. Exact source/build/evidence
hashes and measured outcomes are in IPTV-SAMPLE-LOAD-VALIDATION-20261006.json.

Final validation: full app compile (448s); 204 core tests (2.186s); 285 full IPTV JVM tests in
34 suites with zero failures/errors/skips (7.730s test / 384s build); current-fixture
harness builds (31s). The Android cancellation case explicitly delegates to the real stager
before cancellation. Initial pre-hardening passes and the intermediate hardened
harness result are retained separately; no new Android execution is claimed.
