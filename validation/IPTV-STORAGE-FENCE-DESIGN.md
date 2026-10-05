# Physical capture storage observations and margins

Development continuation from 4606418, 6 October 2026. Internal integration;
production pause/timeshift/recording controls remain disabled.

CaptureStoragePolicy requires an explicit positive caller-selected minimum free
margin and a fresh probe of usable bytes, filesystem allocation unit and volume
identity. It binds the unit/identity for the store lifetime; invalid/unknown
readings, arithmetic overflow, changed identity/unit or probe failures fail closed.
Caller cancellation/interruption propagate unchanged. The policy does not choose
a production default margin, establish a quota or preallocate disk blocks.

The store exposes minimumStorageOverheadBytes: caller margin, two rounded 1 MiB
index allowances, and 4098 allocation units for worst-case retained/pending file
padding and marker overhead. The 4096-row cap is conservative even for small
spools. Runtime overhead must meet that bound. Governed SharedCaptureRuntime now
requires a guarded store and observes room for remaining retained payload, the
maximum pending segment and admitted overhead before upstream/consumer start.
Actual existing file allocation is reflected in usable space; future eviction
is never credited. Logical account/memory/disk leases remain held until existing
consumer/transport/store closure confirms. Denying a new consumer does not stop
an existing recording; an uncertain close still retains its infrastructure lease.

A direct legacy store can omit the guard for byte fixtures; such a store is
rejected by governed runtime sharing. No shipping capture/player factory is
connected yet. This compatibility path must not be used to enable controls.

Guarded append observes room for the full maximum segment plus index headroom
before creating its pending file or consuming input, then checks each write's newly rounded
allocation plus index headroom. It checks again after segment sync, before
allocating index.new and after index sync before atomic promotion. The store
retains last-good rows/files and pins on rejection, does not consume a sequence
number and discards uncommitted media. A failed index promotion can leave the
known task-owned index.new; scoped recovery clears it before future append.
Existing stores can reopen/read retained media with little free space; writes
and governed admission remain blocked. Initial marker creation is guarded.

Physical failures produce explicit STORAGE_BLOCKED in SegmentCaptureTransport.
It closes its one current body/source and performs no retry or further pull.
Committed readers report STOPPED after draining the retained bytes, preserving
the explicit producer outcome; caller/runtime still owns confirmed cleanup.
Checks during source copying are outside the store monitor. Index publication
and its filesystem observations still occur under the monitor; no zero-latency
or storage-latency measurement is claimed.

AndroidCaptureSpaceProbe uses Os.statvfs available blocks and fragment/block unit,
with Os.stat device identity before/after the reading. It supports private local
filesystem observations; it does not establish document-tree/USB or SMB support.
A separate JVM fixture uses host JDK FileStore usableSpace and a small task
temporary directory, without filling or modifying the volume. This Windows JDK
reports 512-byte sectors through getBlockSize; Win32 GetDiskFreeSpace showed
8 sectors per allocation cluster (4096 bytes). The corrected host-only fixture
uses a bounded noninteractive task-owned PowerShell/Win32 query for the cluster
and JDK volume serial, never sector size as allocation geometry. Non-Windows
hosts use their FileStore blockSize. Exact measured readings are in the report. Those
host checks cannot certify AM9 allocation semantics or choose device margins.
Two Android fixtures cover the real statvfs probe and a guarded commit/reopen;
they are build-only while device execution remains deferred.

These are observations, not atomic reservations against other apps. Space may
change after a check; write/sync/move failures still use the store's transaction
and cleanup rules. Filesystem compression/sparse allocation/metadata behavior and
power-loss durability are not newly certified. Required next gates: execute the
Android probe/staging fixtures when the authorised device is awake, choose and
measure the production storage/memory/decoder envelopes, then connect actual
MediaSource/MediaPeriod loading, preroll/seeks and capture-aware lifecycle cleanup.
Durable services/schedules/internal/USB/SMB recording remain separate work.
