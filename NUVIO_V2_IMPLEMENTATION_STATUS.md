# Nuvio V2 implementation status

## Recovery reference

- Base branch: `nuvio-test`
- Base commit: `45e0984c18460d2a65c5d745999011b4314328eb`
- Initial feature HEAD: `45e0984c18460d2a65c5d745999011b4314328eb`
- Latest recorded feature HEAD before this status checkpoint: `3eccf39e03b9f4fb7832b7a2367e09b36144a33b`
- First remote baseline/status checkpoint: `cf3225698a6a95d39b03e92c721f2b62bd892dcb`
- Earlier local-only baseline commit: `f57a9c2500127885f87b72d352a8781ee527a914` (same status contents; not a remote ancestor).
- Resolve the checkpoint's own HEAD with `git rev-parse HEAD`; a commit cannot embed its own hash.

## Progress

- Current phase: Phase 0 — Android baseline established; integrating diagnostics next.
- Completed: toolchain provisioning, debug/release baseline builds, full lint inventory and executable unit-test baseline.
- Read the full 413-line work brief, 3,714-line specification and prior status before changes.
- All existing feature-branch history is preserved. Application source remains identical to the recorded base at this checkpoint.

## Validation

- Temurin JDK 17.0.20.1 was downloaded and checksum-verified; Gradle 8.13 and Android SDK 36 work on this Windows host.
- Debug and R8-optimized release APKs built for ARM64 and ARMv7, using development debug signing only.
- Full unit suite after fixture compilation repairs: 1,325 tests; 37 failures and one skipped. One failure was a Windows sparse-file allocation problem, repaired in this checkpoint.
- Focused rerun of all six changed test classes: 100 tests; 99 passed, one existing live-credential integration test skipped; no failures.
- Full lint: 3,592 existing errors, 1,835 warnings, 22 hints. No lint rules were disabled and no lint suppression baseline was added.
- Release-tooling tests: 19 passed. Bash cannot start in this Windows sandbox; its syntax check passed in the earlier workspace.
- See NUVIO_V2_BASELINE_VALIDATION.md for commands, exact baseline APK hashes, test failures and lint inventory.
- No runtime performance results yet. D8/R8 report stale startup-profile entries; profile regeneration is outstanding.

## Architecture

- Original and V2 will share repositories, ViewModels, navigation, profile/session and playback state.
- Original remains the safe opt-in rollout default; its existing scale/theme preferences must remain intact.
- UI scale and visual quality are device-local and independent of each other and HDMI output mode.
- The proposed 1280 x 720 dp reference is provisional pending paired device calibration.

## Known issues and remaining work

- Remaining unit failures include stale expectations, missing service configuration and behavior requiring separate triage. They precede V2 changes.
- Existing lint debt includes translations, opt-in annotations and resource/API usage. Compare V2 results against the saved baseline rather than claiming a clean repository.
- Command-line push is blocked by the Windows Git credential helper; the connected GitHub integration is available for fast-forward checkpoints.
- No connected ADB devices or installed TV emulator images were found. The owner has been asked for reachable Ugoos / Fire TV device details while implementation proceeds.
- Continue through diagnostics, preference/renderer foundation and scale implementation. Broader V2 screen, quality, profile-ident, player and benchmark work remains.

## Remaining physical-device validation

- Ugoos AM9 Pro and Fire TV Stick 4K Max logical-canvas calibration on the same TV, with matching rows and output settings.
- Grouped diagnostics procedures and exact diagnostic APK hashes must accompany the first installable checkpoint.
- Release-mode 2 GB/4 GB performance, D-pad/focus, 1080p/4K, AFR and player-overlay validation remains unperformed.
- No final PR, merge or release has been created.
