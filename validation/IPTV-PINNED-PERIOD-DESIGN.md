# Finite pinned Media3 period and sample delivery

Development continuation from ae0c08b, 6 October 2026. Internal components;
production playback/capture controls remain unconnected and disabled.

PinnedCaptureSegmentPeriod implements the actual shipped MediaPeriod/SampleStream
interfaces over ONE complete staged segment. Its stage factory calls the existing
transactional TS stager, requires the exact window/proof, verified EOF and open
input, and transfers the pin only on successful construction. Failure/cancellation
leaves ownership with the caller. Constructor component checks cover counts,
monotonic timestamps, initial IDR, format/DRM, initialization/dimension and
compressed byte/sample caps. Callers must supply actual admission and measured
aggregate heap/decoder budgets; these logical caps do not reserve codec memory.
InspectedCaptureInput exposes synchronized isClosed to reject an already closed
or unexpectedly externally closed owner, including after verified EOF.

prepare binds one playback-access thread and reports readiness after the finite
resource has already been staged. All sample/period accesses stay on that thread;
close can run after renderer shutdown on another thread. Track selection validates
array shape, exact group/stream ownership, one format per group and duplicate
choices before changing state. Retained selections keep their cursor when the
presentation position is unchanged; deselected streams are fenced. Changed
presentation positions reset selected cursors to the segment start.

Sample reads deliver format, encoded payload/time/keyframe and final-sample/EOF
metadata into actual DecoderInputBuffer. PEEK does not advance; REQUIRE_FORMAT
does not consume a sample; OMIT_SAMPLE_DATA avoids payload allocation. Failed
buffer allocation/copy does not consume a sample. Private compressed arrays are
copied, never exposed. EOF retains the input/pin until explicit close. Video skip
advances only to an available keyframe; audio can advance to a preceding frame.
Skipping beyond the finite resource ends that track, not a live producer.

Seeking clamps/floors to actual staged video sample times. Seek-parameter sync
tolerances choose an available sync point before frame quantization. Initial IDR
and the complete audio phase, including negative audio preroll, remain available
to decode from the beginning. No decode-only flag is invented: the shipped common
and decoder API has no such input flag. Inspected shipped MediaCodecRenderer
bytecode classifies output presentation times below getLastResetPositionUs as
decode-only. That observation does not execute or certify renderer behavior.
Actual normalized audio codec acceptance, video/audio output discard and player
reset/offset behavior remain device/integration gates. The period never calls the
core seek controller's acknowledgement; a later governed player must acknowledge
only the exact completed request after actual seek/render confirmation.

This resource is entirely preloaded and finite. Buffered/next-load positions are
TIME_END_OF_SOURCE; continueLoading returns false. These values do not establish
end of a running capture or a complete epoch with other unstaged rows. Do not wire
this finite period into the dynamic epoch timeline as a live-tail loader. Actual
MediaSource/MediaPeriod loading, waiting/final/error/expiry and explicit epoch
transitions remain separate implementation work. No network or decoder opens here.

Close fences further reads before input closure. A failed close retains its input
handle/pin and can be retried; only confirmed closure clears the encoded batch and
reports released. It confirms input/pin ownership only. A future consumer must
release/confirm renderers, decoder, period/pins and upstream in the right order
before shared admission releases account, display/audio and memory/storage leases.

JVM fixtures manually verify real pinned resource bytes, then use SYNTHETIC sample
payloads/formats to exercise actual Java buffer/period APIs, selection/seek/read
behavior and closure/thread fencing. They do not execute Android TS extraction,
a renderer or codec. Two Android fixtures cover the real TS-staging factory and
stream counts/pin lifetime plus cancellation ownership. They remain unexecuted
while AM9 is observed Asleep. The harness reuses unchanged shipped Java player/
data-source AARs and the already-used 1.8.0 decoder dependency; it instantiates no
player/UI/native decoder and strips INTERNET and ACCESS_NETWORK_STATE. No main
application dependency/version changed. Check the report for final build/JVM/APK
hashes. Final app compile, 191 core tests, 253 IPTV JVM tests and final-source
harness builds passed; the preliminary harness result is retained separately.
