# IPTV cloud review and continuation

Repository: ysosrs123/NuvioTV-Fork
Branch: iptv/player-binding (continues iptv/wip 8b84c11; iptv/wip is unchanged)
Last fully validated local checkpoint: b68985aafcd78f2b096e7073082f98c3c85bb874

Current status: read the "Player-binding cloud continuation" section of
validation/IPTV-NEXT-SESSION-HANDOFF-20261006.md, then
validation/IPTV-CAPTURE-PLAYER-CLOUD-20261006.json and
validation/IPTV-CODE-REVIEW-20261006.md. The probable cause of the two player-fixture
timeouts is fixed in source and covered by a JVM regression; the device fixtures have
not been rerun. The text below describes the iptv/wip transfer and remains accurate
for that commit.

The user explicitly authorized publishing this WIP branch on 6 October 2026 for
another agent to review and continue in a cloud container. Use this repository
on iptv/wip. The repository name NuvioTV-Fork is correct for this transfer;
the warnings about the wrong Fork folder refer to a separate dirty local checkout.
The Windows-only workspace and offline toolchain instructions apply to local PC
operations. Cloud work uses its own repository directory; do not try to access E:.
This file overrides older statements that current WIP is uncommitted or that only
local continuation is authorized. No automation, release, deployment or PR is requested.

## Read in this order

1. validation/IPTV-NEXT-SESSION-HANDOFF-20261006.md
2. validation/IPTV-CAPTURE-PLAYER-WIP-20261006.md and its JSON report
3. validation/IPTV-PROGRESS.md and validation/IPTV-RELEASE-NOTES-DRAFT.md
4. Relevant source/period/reader designs linked in those documents

All prior IPTV implementation commits, synthetic TS fixtures, tests and these
notes are included in this branch. The current unfinished player binding is also
included. It is WIP: both actual Android player fixtures still time out after
30 seconds. An earlier missing androidx.collection harness class was corrected
using the full app's existing 1.5.0 version. The generic ownership/core tests pass.
No successful player rendering, preroll discard, seek acknowledgement or production
memory/capacity claim follows from the failing player fixtures. Controls stay disabled.

The last validated checkpoint b68985a passed the full app Kotlin compile,
317 IPTV JVM tests in 38 suites and 27 focused AM9 capture/codec cases. That evidence
belongs to that checkpoint. Comment/KDoc cleanup for this transfer changes no Kotlin
executable tokens; historical byte hashes remain associated with their recorded
checkpoints. Current transfer hashes and the new core run are recorded in
validation/IPTV-CLOUD-TRANSFER-20261006.json.

## Evidence and environment

Key raw logs and 38 validated JVM XML reports are versioned under
validation/cloud-handoff/20261006/evidence. A local .gitattributes preserves their
exact bytes. manifest.json records SHA-256 and size. Original reports often refer
to validation/iptv-core; the copied logs have the same filenames in this archive.
APKs, local.properties, credentials, SDK/JDK binaries and Gradle caches are not
included. APK hashes remain in the measured reports. The existing shipped AARs
and synthetic media are tracked in the repository.

Review and implementation can proceed in the cloud. Run host checks if the cloud
has the required existing toolchains/dependencies; report limitations explicitly.
scripts/check_iptv_core.py accepts JAVA_HOME and GRADLE_MODULE_CACHE; its Windows
cache default must be overridden with the cloud's module cache. Do not upgrade
versions to make a check pass. AM9 instrumentation, renderer/hardware checks and
physical USB/SMB validation require the local device and cannot be claimed from
cloud review. Do not repeatedly rebuild to reproduce unchanged historical checks.

## Review priorities and rules

- Review the admitted source/player lifecycle and decoder floor; inspect why the
  two CaptureVideoPlayerAndroidTest cases remain pending instead of ENDED/FAILED.
  Add useful bounded state diagnostics before changing timeouts or ownership rules.
- Preserve real shared acquisition, live WAITING vs EOF, exact epoch/expiry policy,
  stable PTS/audio phase and reservations until confirmed final cleanup.
- Preserve independent consumers. Fixture leases are not durable recording.
  Complete the remaining recording/provider/UX scope documented in the main handoff.
- No AI comments; use minimal or no KDoc. Put architecture/validation explanations
  in handover/design documents. Keep existing component versions.
- Leave automations paused. Do not contact providers, copy credentials, change
  device/account/power/CEC/settings/recordings or publish releases/deployments.
- Make changes on this WIP branch or a separate review branch derived from it;
  do not start from the fork's default branch. Keep current release notes and
  handover accurate, distinguishing passes from failing/untested work.
