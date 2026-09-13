# Nuvio V2 implementation status

## Recovery reference

- Base branch: `nuvio-test`
- Base commit: `45e0984c18460d2a65c5d745999011b4314328eb`
- Initial feature HEAD: `45e0984c18460d2a65c5d745999011b4314328eb`
- Latest recorded feature HEAD before this status checkpoint: `69be3e98604c4f4a7e8046814473014353aee89d`
- First remote baseline/status checkpoint: `cf3225698a6a95d39b03e92c721f2b62bd892dcb`
- Earlier local-only baseline commit: `f57a9c2500127885f87b72d352a8781ee527a914` (same status contents; not a remote ancestor).
- Resolve the checkpoint's own HEAD with `git rev-parse HEAD`; a commit cannot embed its own hash.

## Progress

- Current phase: Phase 3 next — paired Home/dialog scale calibration and Ugoos output-mode checks pass. Physical AFR and broader release performance validation remain outstanding.
- Completed: toolchain provisioning, debug/release baseline builds, diagnostics, device-local preference migration, Original/V2 selection and automatic/manual scale foundation.
- Read the full 413-line work brief, 3,714-line specification and prior status before changes.
- All existing feature-branch history is preserved. Diagnostics are available from Advanced settings and Debug settings without changing playback behavior.

## Validation

- Temurin JDK 17.0.20.1 was downloaded and checksum-verified; Gradle 8.13 and Android SDK 36 work on this Windows host.
- Debug and R8-optimized release APKs built for ARM64 and ARMv7, using development debug signing only.
- Full unit suite after fixture compilation repairs: 1,325 tests; 37 failures and one skipped. One failure was a Windows sparse-file allocation problem, repaired in this checkpoint.
- Focused rerun of all six changed test classes: 100 tests; 99 passed, one existing live-credential integration test skipped; no failures.
- Full lint: 3,592 existing errors, 1,835 warnings, 22 hints. No lint rules were disabled and no lint suppression baseline was added.
- Release-tooling tests: 19 passed. Bash cannot start in this Windows sandbox; its syntax check passed in the earlier workspace.
- See NUVIO_V2_BASELINE_VALIDATION.md for commands, exact baseline APK hashes, test failures and lint inventory.
- No controlled runtime performance results yet. D8/R8 report stale startup-profile entries; profile regeneration is outstanding.

## Architecture

- Original and V2 share repositories, ViewModels, navigation, profile/session and playback state.
- Original remains the safe opt-in rollout default; its existing scale/theme preferences are retained. Switching renderer changes the presentation context without replacing the navigation graph.
- UI scale and visual quality are device-local and independent of each other and HDMI output mode.
- A separate device-level V2 aesthetic store serves the pre-profile snapshot. Hardware preferences do not enter profile synchronization. Existing color themes supply accents while V2 styles own neutral surfaces.
- Existing explicit scale values, including 100%, migrate once to V2 Manual mode. New installs use Automatic; the old Original store remains intact.
- Automatic resolves from raw window pixels/base density, clamps its base to 75–115%, applies multiplicative fine-tune, then clamps to 72–120%. Manual supports 75–115%. Geometry updates wait during the player route and while the Activity is not resumed.
- The 1280 x 720 dp reference produces matching Home geometry on both devices on the same TV: all 28 labeled elements match in position and size at 4K/59.94 Hz.

## Known issues and remaining work

- Remaining unit failures include stale expectations, missing service configuration and behavior requiring separate triage. They precede V2 changes.
- Existing lint debt includes translations, opt-in annotations and resource/API usage. Compare V2 results against the saved baseline rather than claiming a clean repository.
- Command-line push is blocked by the Windows Git credential helper; the connected GitHub integration is available for fast-forward checkpoints.
- ADB discovered the Ugoos AM9 Pro: API 34, 1920 x 1080 window, density 240 dpi (1.5), logical canvas 1280 x 720 dp, active output 3840 x 2160 at 59.94 Hz, approximately 3.63 GiB physical RAM. The isolated debug APK installs and reaches the account/guest entry screen without crashing. A single cold Activity launch took 1,718 ms; this is not a release/startup benchmark.
- The owner authorized Fire TV ADB and confirmed the TV was switched to the Ugoos input. Both devices run the separate validation package; existing Nuvio packages and configured app data remain untouched.
- Phase 2 explicitly requires testing both devices on the same TV before proceeding. Broader V2 screen, quality, profile-ident, player and benchmark work remains; unimplemented options are not exposed as working controls.
- All 35 existing locale resource directories have translations for the new settings; native-speaker review remains outstanding.

## Remaining physical-device validation

- Ugoos AM9 Pro: 1920 x 1080 window, density 1.5, Automatic 100%; Fire TV AFTKRT (API 30, ARMv7 OS, approximately 1.63 GiB RAM): 1920 x 1080 window, density 2.0, Automatic 75%. Both resolve to 1280 x 720 dp with 4K/59.94 Hz HDMI output. Existing device size/density overrides were not changed.
- Fire TV fine-tune -10/0/+10 resolves to 72/75/83%; Manual 75/100/115 works and keeps slider focus. Original restores its saved 100% scale and AMOLED controls. V2 preferences persist across relaunch on both devices.
- The grouped procedure is in validation/README.md. A platform Dialog density reset found on Fire TV was fixed for V2; Original dialog behavior is retained. Both scale pickers now have identical title, option, selection and focus bounds, including a 36 px title line. Dismissal restores the scale row's focus on both devices.
- Release-mode 2 GB/4 GB performance, D-pad/focus, 1080p/4K, AFR and player-overlay validation remains unperformed.
- No final PR, merge or release has been created.

## Diagnostics checkpoint

- Bounded 30-second UI-thread frame ring with percentile and jank summaries; no per-frame Compose emissions.
- Report captures the activity window before opening its dialog, separating raw density, effective canvas and HDMI mode.
- Debug APK build and four diagnostics tests pass. Full lint is unchanged: 5,449 findings, zero added or resolved.
- Optional validation/isolated-ui.init.gradle builds a separate com.nuvio.tv.v2.validation application to preserve the existing installed app and its data. The isolated debug package and launcher label were verified and the ARM64 APK installed successfully.

## Foundation regression results

- Full suite before the final dialog fix: 1,339 tests, 1,302 passed, 36 failed, one skipped. Every failure matches the saved pre-V2 baseline. All 14 diagnostics, geometry, migration, palette and outgoing-player visibility tests pass.
- After the dialog fix, all 19 focused tests pass; debug and optimized release APKs build for both ARM architectures. Lint has 5,448 findings: zero added, one existing warning resolved. The combined Gradle invocation still fails because of the existing 3,592 lint errors; no rules were suppressed.
- Final isolated release APK SHA-256: ARM64 `06b912a22c1e4dc0372b84fc53be6809a43be13e25929db6891c2e20cd9e9ba5`; ARMv7 `1c1958bff7306bb018a7055f2f25d31250c1c3c26f2c788f6d70e9134313bffb`. Development signing only.
- Geometry remains frozen while the internal player is visible, including its outgoing navigation fade, and while an external player pauses the Activity. The route/visibility guard has unit coverage; physical AFR acceptance remains pending.
- The first 1080p capture encountered another foreground app and is invalid. The repeat verified the validation Activity in front and actual output at 1920 x 1080/59.94 Hz, then 3840 x 2160/59.94 Hz after restoration. Automatic stayed at 100% with identical Appearance geometry and scale-row focus. The original user-preferred display mode (`null`) was restored.
- Mixed-screen diagnostics are not controlled performance results. Release Macrobenchmark P95/P99, memory and frame-budget acceptance remain unclaimed.

