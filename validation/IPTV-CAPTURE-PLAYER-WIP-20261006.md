# Nuvio IPTV current player-binding work in progress — 6 October 2026

Authoritative folder: E:/Codex/NuvioTV/work/NuvioTV-IPTV. Cloud transfer branch: codex/iptv-wip.
Last validated committed checkpoint: b68985aafcd78f2b096e7073082f98c3c85bb874.
Read IPTV-NEXT-SESSION-HANDOFF-20261006.md first for all standing restrictions.

## Current transfer state

The seven implementation/test/build files below are included on codex/iptv-wip
for the user-authorized GitHub/cloud transfer. Read ../IPTV-CLOUD-HANDOFF.md first.
The last validated implementation remains b68985a; the newer player code is WIP.
There is no active task build or instrumentation session. All automations stay
paused. No new agent/chat was created. GitHub publication of this WIP branch is
explicitly authorized; no release, deployment or PR is requested.

- app/src/main/java/com/nuvio/tv/core/iptv/SharedCaptureRuntime.kt: added consumer
  minimum decoder reservation count and pre-start reservation check.
- app/src/main/java/com/nuvio/tv/core/iptv/CapturePlaybackConsumer.kt: composite
  source/player consumer, startup ordering, player-confirmed close before source
  cleanup, retained uncertain ownership and independently confirmed cleanup halves.
- app/src/main/java/com/nuvio/tv/data/iptv/CaptureVideoPlayer.kt: internal video-only
  ExoPlayer owner over exact CaptureEpochMediaSource and caller-owned Surface;
  no audio renderer/sink. One posted release and bounded shared completion across
  cancellation/timeouts; thrown or callback release errors remain uncertain.
- app/src/test/java/com/nuvio/tv/core/iptv/SharedCaptureRuntimeTest.kt: decoder-floor
  regression. CapturePlaybackConsumerTest.kt: three cleanup/start ordering cases.
- app/src/androidTest/java/com/nuvio/tv/data/iptv/CaptureVideoPlayerAndroidTest.kt:
  two actual shared-transport/ExoPlayer/offscreen-ImageReader cases at start0/1000ms;
  attempted renderer preroll and blocked-application-thread close checks.
- tools/iptv-device-tests/build.gradle.kts: added the full app's existing cached
  androidx.collection:collection-jvm:1.5.0 for actual async codec callbacks. No
  component upgrade. Full-app baseline version is visible in the existing
  app/build/outputs/sdk-dependencies/iptvPrototypeDebug/sdkDependencies.txt.

## Measured outcomes and unresolved failure

211 core tests pass in 2.326s. The current isolated harness builds in 56s. Earlier
new fixture compile failure was a wrong snapshot API call, fixed before device use.
The first device run crashed because the isolated harness lacked CircularIntArray.
The unchanged full app already includes its androidx.collection1.5.0 runtime;
adding that same cached version corrected the missing-class packaging failure.

The subsequent player fixture run executes two tests and both TIME OUT waiting
30 seconds for ENDED/FAILED. The root cause remains unresolved. Do not claim
successful offscreen rendering, preroll discard, exact seek acknowledgement,
blocked-release retention or measured capacity from these failed cases. The generic
core ownership tests passed; the actual Android player path has not passed yet.
Capture controls remain disabled. No full-app compile or full JVM rerun has been
performed for this WIP. Last successful full checks are the b68985a report.

Next inspect the cached reader/source/player state and exact test-owned renderer
metrics at timeout, with bounded synthetic fixtures. Diagnose buffering/sample /
EOS/surface behavior before changing timeouts or ownership rules. The two failed
cases currently provide insufficient state diagnostics. Preserve explicit live
WAITING vs EOF, same admission/shared transport, exact epochs and confirmed native
cleanup. Then complete app/JVM/device checks and commit a validated checkpoint.

## Evidence and device cleanup

Exact WIP source/APK/log hashes and outcomes: IPTV-CAPTURE-PLAYER-WIP-20261006.json.
Logs are local under validation/iptv-core/capture-player-*20261006*; that folder
is Git-ignored locally. Copies are included in cloud-handoff/20261006/evidence
with exact byte hashes. The reports and handover/progress/release notes are in validation.

Only AM9 serial192.168.10.60:5555 was used. It was Awake during tests and Dreaming
at final cleanup. Both com.nuvio.iptv.validation.test and com.nuvio.iptv.validation
were successfully uninstalled; package-path checks return no path. Reverse list
is empty. Prototype/comparator apps, accounts, recordings and settings were left
alone. No activity, wake, power/CEC, audible output or provider requests.

AM9 availability must be rechecked. Full app toolchains/caches are outside this
folder at the paths documented in the main handoff. All IPTV source, project docs,
local validation reports/logs/APKs and this checkout's .git are in this folder.

Comment/KDoc cleanup for the cloud transfer leaves Kotlin executable tokens unchanged.
Historical test/source hashes remain recorded; current transfer hashes are in
IPTV-CLOUD-TRANSFER-20261006.json. The code rule is no AI comments and minimal/no KDoc.
