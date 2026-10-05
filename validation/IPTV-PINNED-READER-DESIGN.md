# Asynchronous pinned reader consumer

Internal continuation from 6842df1, 6 October 2026. Capture/player controls remain
unconnected and disabled; this is a reader owner, not a decoder or live MediaSource.

PinnedCaptureReaderConsumer implements OwnedCaptureConsumer for the existing
SharedCaptureRuntime. Construction opens nothing. Its single start launches one
worker on the supplied IO dispatcher, commits the exact still-pending seek through
CaptureSeekController and stages one pinned segment into the actual finite
PinnedCaptureSegmentPeriod. The production staging function is unchanged; an
internal fixture seam substitutes synthetic samples/faults for host JVM tests.
There is no network open, polling, segment prefetch, replacement or automatic retry.

CaptureSeekController.isCurrentCommitted checks the exact controller/request,
pending anchor and committed identity without acknowledging or changing them.
READY publication checks that identity after all staging, and borrowing checks it
again. A newer request or cancellation prevents late publication. Readiness,
preparation and cleanup never acknowledge a seek or clear a newer pending anchor.
A future player must also serialize/fence its commands and acknowledge only after
actual seek/render confirmation; readiness is not that confirmation. Borrowing does
not transfer closure; each handoff checks the current/open request. An already
borrowed period remains pinned until explicit close. Clearing a seek anchor after
an eventual successful render is separate from the future player selection owner.

States are NEW, LOADING, READY, EXPIRED, STALE, FAILED, CLEANUP_REQUIRED, CLOSING and
CLOSED. Expired or foreign/superseded commits do not substitute another segment.
The worker keeps the returned period before checking cancellation, avoiding a
successful stage being lost during the dispatcher/cancellation race. Failure or
staleness closes the untransferred input or period off the calling thread. Cleanup
failure retains that exact handle and pin. A successfully published finite period
keeps its pin until explicit closure, including EOF and a later stale borrow.

close fences borrowing first, cancels and joins the sole staging worker, then
launches/joins one cleanup job. It never concurrently closes an input still being
read. A timeout returns false and keeps ownership; a later explicit close waits for
the same worker/closer, or retries a completed failed cleanup. Confirmed input/pin
closure is required for true and CLOSED. Close-before-start opens nothing and fences
restart. There is no broad coroutine/process shutdown or silent retry.

An admitted parent consumer must account for encoded staging, parser/transient
copies, object overhead and future decoder/renderer memory before construction.
Staging caps alone do not measure or reserve aggregate heap. SharedCaptureRuntime
keeps the consumer memory lease until close returns true; its infrastructure
account/spool lease remains until producer and store also close. The JVM integration
fixture checks this with a separate existing recording consumer and a failed pin
close. Its logical byte reservation and synthetic payloads are NOT a production
memory budget, physical-space benchmark or recording implementation.

This reader opens no decoder, display or audio device and claims no decoder/player
closure. Any future renderer/decoder borrower must hold its separate admitted
ownership and stop/confirm it BEFORE closing the reader. Do not register this reader
alone as an actual playing viewer or release decoder reservations on reader closure.
Dynamic source/epoch-period loading, WAITING vs terminal/error/expiry transitions,
normalized negative audio, renderer preroll, epoch offsets and visible playback
remain independent gates. The one finite segment is not live-tail EOF.

Twelve JVM fixtures exercise real committed files, inspection, core seeks, asynchronous
ownership and SharedCaptureRuntime with SYNTHETIC encoded samples. They cover stale
and expired opens, cancellation before/after transfer, blocking-worker timeout,
failed staging, retryable/blocked pin close with one closer, retained cleanup
failure, close-before-start and preservation of another
recording consumer/reservations. Two Android fixtures use the actual TS stager;
they compile only until the authorized AM9 is awake. No provider/device installation,
wake, power/CEC, accounts/settings or recordings are needed for host validation.
Check IPTV-PINNED-READER-VALIDATION-20261006.json for final measured results/hashes.
