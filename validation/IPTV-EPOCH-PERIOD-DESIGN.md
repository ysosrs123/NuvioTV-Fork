# Growing captured-epoch MediaPeriod and exclusive sample borrowing

Internal continuation from 34a4937, 6 October 2026. Controls stay disabled.
This is actual MediaPeriod/SampleStream delivery with explicit cached refresh,
not yet a MediaSource, admitted player or renderer confirmation.

CaptureEpochPeriod consumes cached reader snapshots only. Construction validates
exact stable Timeline UID/epoch and contiguous batches, then acquires ONE exclusive
identity borrow. Stale/copied snapshots, another active borrower or foreign UID
cannot acquire it. No input, file, pin, IO job, codec or network is opened here.
All MediaPeriod/SampleStream operations are confined to prepare's playback thread.
Close fences streams first and may run after renderer shutdown on another thread.

Playback-thread refresh accepts only an exact current reader snapshot, newer
revision, same UID/epoch/formats and retained batch identity prefix. It extends
borrow ownership before publishing batch growth. The future MediaSource must
dispatch updates/callbacks on the playback thread. Existing stream row/sample
cursors and absolute epoch PTS survive growth. All sample bytes copy into caller
DecoderInputBuffer, with format, keyframe, peek/omit and seek/skip support.

continueLoading never requests another probe; only capture hints/explicit owner
requests drive IO. Player load callbacks cannot create automatic local polling.

WAITING, CAPACITY and LOADING tails yield RESULT_NOTHING_READ, never EOS or an
invented frame. Only refreshed ENDED (producer COMPLETE plus exact drained cursor)
emits EOS/LAST_SAMPLE. Stopped/expired/failed/cancelled/blocked/closing owners raise
an explicit error. Epoch boundaries raise an explicit error and never automatically
navigate to another epoch. EOF retains borrower/input/pin/runtime ownership.
At capacity, a reader cannot establish the next boundary until it can poll again;
metadata refresh alone cannot manufacture successful completion.

Seeks clamp to retained actual video frames and start the containing batch at its
initial IDR, including that batch's audio phase/preroll. Runtime renderer discard,
negative first audio and timeline offset behavior remain device gates. There is no
player seek command or exact pending-target/render acknowledgement here.

Borrow ownership now prevents the reader's asynchronous release and queue closer
from dropping any held batch. Reader close first fences publication/borrowing and
cancels/joins IO worker/observer; it returns false while a period borrow is active.
The actual shared runtime consequently retains the reader reservation and leaves
other consumers running. After period shutdown/explicit borrow release, a retry
can run the sole queue closer and confirm reader closure. Period close confirms
ONLY period ownership, never decoder/renderer shutdown. Parent must separately
stop and confirm those consumers before calling it.

Explicit retireConsumedPrefix is available after caller confirmation of consumed
buffers/renderers. It checks all selected track cursors have finished those rows,
keeps at least one row, atomically transfers only that prefix back to the reader,
fences cached snapshots and queues input closure off-thread. Input-close failure
retains pins/charges and blocks further loading. Confirmed release permits bounded
refill; refreshed Timeline eviction offset and UID preserve the epoch origin/PTS.
Old rows cannot be sought through this period after retirement. discardBuffer does
not silently make this policy decision; no automatic eviction or provider retry.

Nine JVM period cases exercise live waiting then growth, true complete EOS,
stop/epoch errors, borrow-retained reader/runtime closure and independent recording
consumer, real frame seek/preroll/peek/omit, consumed-prefix refill, exact snapshot,
selection and thread fences. Files/inspection/pins/reader/runtime are real; encoded
payloads/formats, recorder and budgets are synthetic. Two Android cases use actual
TS staging and sample delivery, but compile only while device validation is deferred.

Logical encoded-memory floors are unchanged and do not measure transient Java /
parser/native/decoder/graphics memory. Dynamic MediaSource callback dispatch,
actual governed player/renderer ownership, device offset/preroll/seek validation,
measured budgets and durable pause/record/storage/provider/UX scope remain required.
The matching evidence report contains final results and exact source/APK/XML/log
hashes. No component upgrade, device/provider operation or enabled control.
