# Shared capture ownership and local byte reader

Development checkpoint, 5 October 2026. This layer is not enabled in the foreground player and does not yet provide playable timeshift or recording.

## Ownership

`SharedCaptureRuntime` reserves an infrastructure consumer before constructing a pipeline. Its account acquisition, capture memory and full spool storage budget remain held until the last user consumer has closed, the producer has confirmed closure, and the store has released its lock. Storage includes retained bytes, one pending segment and a caller-supplied overhead margin. This is logical admission; physical allocation/free-space enforcement remains outstanding.

Each viewer/recorder has a separate consumer lease. A consumer must confirm decoder, local reader and retention-pin closure before its reservation is released. Closing one viewer leaves another recorder/viewer and the producer running. Failed closure retains its reservation. Once the final consumer closes, a failed producer/store close fences new joins until `retryClosing` succeeds. Forgotten local-reader pins cause store close to fail, preserving the infrastructure reservation even if the producer already stopped.

Tokens identify a consumer and acquisition instance; old tokens cannot close a replacement. Shutdown fences new joins and attempts every consumer. `closeAll` is also the cleanup path for a cancelled join whose consumer token was never returned. A failed normal join returns its pending consumer token when consumer cleanup is uncertain. Factories must release partial constructions if they throw and cannot open media/decoders during construction. This layer does not automatically retry failed acquisition or producer startup.

An acquisition found in admission without an owned pipeline is rejected for sharing. Existing direct foreground playback still uses `LivePlaybackRuntime` and still rejects capture sharing. Production integration must use the same admission instance and an actual shared transport; merely giving two runtimes matching keys is insufficient. Audio/display ownership is reservation metadata; real player handoff and aggregate VOD/multiview integration remain outstanding.

## Bounded ingestion

`SegmentCaptureTransport` pulls one complete segment body from `CaptureSegmentSource`, streams it through the store's byte-limited append, closes the body, and only then asks for another. It has no unbounded queue, speculative prefetch or automatic retry. Oversize/read/publication failures stop ingestion; protected retention reports backpressure and stops the source without replacing committed rows.

Source protocol adapters must fence late opens, bound their own protocol parsing/network operations and confirm request closure. No HTTP/HLS/MPEG-TS segment parser is supplied here. Cancellation fences the worker, closes the source to unblock reads, and waits for producer termination within a timeout. A body returned late is closed without publication. Failed body close retains its handle for cleanup retry. Body-close I/O runs outside the state monitor; retry cleanup runs on an independent worker. A timeout retains that cleanup attempt, and subsequent retries wait for it instead of concurrently closing the same body. False/throwing/timed-out closure cannot authorize release of the pipeline reservation.

Complete/backpressure/failed states describe ingestion, not proof that provider quota has already been reclaimed. The runtime releases capacity only after explicit closure confirmation. Source close must be idempotent, concurrency-safe and cancellation-cooperative. No provider quota-release latency is measured.

## Local reader

`CaptureSegmentStore.openSnapshotFrom` atomically captures and pins an existing contiguous sequence range. `CaptureSnapshotReader` reads those local files in order and stops at a gap or continuity change. It excludes later appends. EOF therefore means completion of that finite snapshot, not a temporarily empty live tail. The pin is held before the first byte is read and remains held through EOF until explicit close.

This is a byte reader suitable as a building block for capture export and later playback adapters. Segment timestamps are caller metadata. They do not prove independent decoding, PAT/PMT/init availability, audio/video alignment, keyframes or safe seek points. No Media3 DataSource, tail-following live reader, local manifest or seek UI is enabled. Store append currently serializes store operations while copying a segment; concurrent playback latency needs further work before this becomes a live reader.

## Next integration requirements

1. Implement a bounded controlled protocol adapter, preserving init/program/codec information and independently proving usable entry points. Do not expose arbitrary byte-chunk boundaries as seeks.
2. Add a live local-reader/Media3 timeline with explicit waiting, ended, expired and discontinuity states. Derive seeks from actual decoder-safe retained ranges. Preserve a pending scrub anchor in both directions and report reliable delay or unknown.
3. Wire production capture/viewer ownership through one admission instance. Validate backpressure UX, capture-aware navigation/profile/background cleanup, physical disk margins and durable leases before exposing pause/record controls.
4. Add foreground service/process-death reconciliation, recording delivery/schedules, USB/SMB and hardware/UI checks. Existing HUD/track/document UI checks still await an active TV/AVR.

Validation results are summarised in IPTV-PROGRESS.md.
