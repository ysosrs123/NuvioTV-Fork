# Owned captured-epoch MediaSource and playback callback gate

Internal continuation from ed528c6, 6 October 2026. Controls remain disabled.
Actual BaseMediaSource and owned reader/observer lifecycle are implemented;
executed Looper/source/player/renderer and measured admission gates remain open.

CaptureEpochMediaSource implements OwnedCaptureConsumer. Construction opens no
job/input/Handler/player/codec/network. Its declared floor delegates the bounded
reader's encoded-resident floor. SharedCaptureRuntime can admit/start it before
opening a new transport. Start owns the actual reader and one IO metadata observer.
Parent admission must include measured extra transient/Java/native/decoder memory;
this floor and synthetic runtime fixtures do not certify aggregate memory.

prepareSourceInternal binds the calling playback Looper/Handler. State hints feed
CapturePlaybackRefresh with at most ONE queued or in-flight callback; notifications
before/during callback execution coalesce without automatic polling. Callback reads
only cached reader snapshots and publishes actual Timeline metadata on that thread.
No queue/store IO or byte staging runs on the playback thread. Post/publication
failure fences retries and becomes a source error; failed removal retains uncertain
closure. A queued/dequeued late runnable cannot publish after the gate closes.

Initially empty RUNNING/WAITING remains unresolved: there is no temporary empty
Timeline/EOS publication. Only true complete with no samples may publish EMPTY.
Growing cached epoch metadata keeps UID/offsets; source creates one exclusive-borrow
CaptureEpochPeriod for an exact current UID, rejects ads and expired/unresolved
start targets, and never automatically navigates an epoch boundary. Explicit
boundary/failure errors remain distinct from successful EOF. Prepared periods
refresh before source publication; period preparation also checks latest cached
metadata to cover updates queued before prepare. Player continueLoading still
cannot start tail polling. No source/period operation acknowledges a pending seek.

releasePeriod validates the actual owned period and fences/releases its borrow.
releaseSourceInternal fences callback publication and cancels the observer, with
no file closure or blocking job join on playback thread. Source owned close first
fences/removes callbacks and joins the observer. Any running callback/removal,
period or BaseMediaSource caller lifecycle still owned prevents true confirmation.
Reader close must separately confirm IO/input/pin closure; its active sample borrow
prevents false queue release. Repeated close retries preserve exact ownership.
Period close also clears its own format/init-data references; external delivered
buffers/formats belong to their separately admitted owners.

Parent must stop and confirm actual renderers/decoder owners BEFORE period/source
release and runtime close. Source/period closure cannot prove renderer/native
shutdown. At EOF, source/period/reader/runtime ownership remains until explicit
release. Source release is single-use; there is no automatic replacement/retry.

Seven host callback-gate cases validate coalescing, follow-up, blocked close,
late runnable, rejected post, failed publication and retryable failed removal.
Three actual MediaSource owner cases validate pre-Looper startup/closure and
actual runtime reservation retention with an independent recorder, using real
reader/files/inspection and SYNTHETIC compressed samples. These do NOT execute
BaseMediaSource.prepareSource or Android Handler callbacks. Two Android cases
use actual BaseMediaSource/HandlerThread/staging/period APIs for waiting/growth /
lifecycle and true zero-sample completion; compile only while AM9 validation is
deferred. No ExoPlayer, renderer/codec, audio/display, provider or production
recording claim is made. Source controls are not wired/enabled in foreground UI.

Next: execute source/period/normalized audio/preroll/offset fixtures on authorized
AM9 while allowed, bind admitted real player/renderer consumers and exact seek /
render acknowledgement, measure aggregate budgets and physical margins. Durable
recording/services/schedules/internal/USB/SMB, providers/grouping and multiview /
guide/Home/Search/Original/V2 scope remains authorized but unfinished. Keep controls
disabled. Check matching evidence for frozen hashes and final measured checks.
