# Governed incremental reader and cached metadata refresh

Internal continuation from 0b82357, 6 October 2026. Controls remain disabled.
This is actual incremental reader ownership/refresh, not an enabled player,
MediaSource, epoch MediaPeriod or durable recorder.

IncrementalCaptureReaderConsumer implements OwnedCaptureConsumer around the
existing actual transactional queue. Construction opens no media/pin/job. Start
creates one persistent IO worker and one metadata-only notification observer.
A conflated signal plus bounded release sets coalesce capture/explicit refresh;
WAITING never starts a timer or polling loop. The worker drains exact rows only
until capacity/waiting/terminal and services explicit releases serially. No
replacement, prefetch beyond the admitted queue, automatic retry or epoch crossing.

SegmentCaptureTransport now publishes committedSequence only AFTER store append
returns its atomic committed row. Its refreshEvents combines sequence/state hints,
including the latest values on subscription. Hints are not timestamps, decode
proof or media bytes. The reader still inspects exact committed rows and samples
producer state before retained metadata. Initial loading plus latest-value hints
cover subscription/start/publication races; notifications during loading retain a
coalesced follow-up. No extra provider requests or HTTP behavior are introduced.

All queue/store access and Media3 timeline construction run on the sole IO worker.
Read-only state/borrowSnapshot accesses use cached immutable lists/timelines and
perform no file/queue IO on the playback thread. Borrow checks the exact delivered
snapshot object and current revision; foreign copies/stale or retiring/closing
views cannot begin a new borrower. Releases fence the delivered revision immediately
and publish updated offsets only after input closure. A metadata build racing that
fence cannot publish. Already borrowed buffers still require their future owner to
stop/confirm renderers before release/close; cached fences cannot confirm decoder
shutdown. No staging/release/completion acknowledges a pending player seek.

Release requests are asynchronous and bounded by actual known batches; acceptance
is not closure confirmation. Duplicate pending/active requests coalesce. Failed
release hides retiring rows, blocks further loading and retains pins/byte charges.
An explicit retry or full close owns cleanup. Queue terminal/boundary identity
survives release and does not become successful EOF or navigation. Observer failure
cannot publish a late successful stage. Reader worker errors retain all ownership;
no abandoned input, array or lease is silently released.

Close fences publication/borrowing first, cancels/joins worker AND observer, then
launches/joins one queue closer off-thread. Timeout/failure returns false and keeps
reservations; retries wait for the same closer or retry a completed failed close.
Late confirmation is normalized to CLOSED with cached arrays cleared on explicit
retry. EOF/terminal alone does not close jobs, pins, store, acquisition or decoder.
Any future player/decoder borrower must release separately BEFORE reader cleanup.

OwnedCaptureConsumer now declares minimumMemoryReservationBytes (default zero for
legacy consumers). SharedCaptureRuntime constructs/checks that lower bound before
starting a new transport or consumer. Incremental reader binds the immutable queue
maxResidentBytes to that floor. Under-reserved joins cannot open upstream/start
loading, and failed/uncertain cleanup preserves existing recorder/infrastructure
leases. Logical encoded-byte floor is NOT a measurement of transient/parser/object,
Java/native/graphics/renderer/decoder memory; parent admission must include measured
overhead. Physical guards remain mandatory and unchanged.

Ten JVM fixtures exercise real files, inspection, queue, runtime and actual transport
notifications with SYNTHETIC compressed batches. They validate cached nonblocking
borrow, refresh/coalescing races, revision/release fences, blocked sole worker/closer,
late confirmation, independent recording leases, observer failure and epoch/EOF
behavior. Three additional core cases validate admission-floor ordering and commit
hints. Two Android cases use the actual stager and cached timeline/epoch boundary;
they compile only until authorized device validation is possible. No decoder,
player, audio/display, device/provider operation or upgrade is needed for host tests.
Actual MediaSource/epoch-period/sample delivery and governed player/renderer
integration with measured/device gates are still required before controls enable.
Check the matching validation report for frozen hashes and final measured results.
