# Nuvio V2 implementation status

## Recovery reference

- Base branch: `nuvio-test`
- Base commit: `45e0984c18460d2a65c5d745999011b4314328eb`
- Initial feature HEAD: `45e0984c18460d2a65c5d745999011b4314328eb`
- Latest recorded feature HEAD before this status checkpoint: `cb16c1f506beff650c6c0954a8087d9b4d8db673`
- First remote baseline/status checkpoint: `cf3225698a6a95d39b03e92c721f2b62bd892dcb`
- Earlier local-only baseline commit: `f57a9c2500127885f87b72d352a8781ee527a914` (same status contents; not a remote ancestor).
- Resolve the checkpoint's own HEAD with `git rev-parse HEAD`; a commit cannot embed its own hash.

## Progress

- Current phase: Phase 3 — quality foundation and benchmark harness implemented; both Fire TV exact-base and V2 comparisons pass. Ugoos performance and exact 24 Hz playback checks remain outstanding. Phase 4 reusable surfaces are being developed separately.
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
- Controlled release-mode base and V2 results now exist for the Fire TV's six browsing/startup journeys (five iterations each). Ugoos comparison results are pending. D8/R8 report stale startup-profile entries; profile regeneration is outstanding.

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
- Paired D-pad/scale and Ugoos 1080p/4K checks pass. Full release-mode 2 GB/4 GB performance comparison, AFR and player-overlay validation remain incomplete.
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

## Quality and benchmark foundation (local work)

- Automatic quality considers API, acceleration/GLES, low-RAM status, total RAM, heap and actual root rendering size. It starts at Performance or Enhanced; hardware specifications alone never choose Maximum. No adaptive jank governor is enabled.
- Device-local initial tier and assessment key are stored separately from the selected quality mode. Central effect tokens contain no layout dimensions. Playback always uses static materials with no live blur. Manual tiers retain the user's choice while falling back to static material when live blur is unsupported.
- The quality selector and diagnostics are implemented, with five new strings in all 36 existing resource locales. Fire TV Maximum selection/dismissal preserves focus and Automatic 75% geometry; diagnostics confirm live blur is disabled on API 30. Automatic was restored before benchmarking.
- All 15 focused V2 tests pass, including four quality-policy tests. Debug and optimized release APKs build on both architectures. Fresh lint remains 5,448 findings, zero added and one existing warning resolved. The combined invocation fails only on existing lint errors.
- Current quality APK SHA-256: ARM64 `53046b076251d4a629e31531d3691e403776f8b89628d5014d1bc5f5b69f3258`; ARMv7 `21f2f58615a946d5e2b13a5274ce0334a67a76c0d48ba83ef6912bd04f97e171`.
- Built the exact immutable base in a detached validation worktree, changing only its application ID to `com.nuvio.tv.v2.baseline` through an external init script. Source files remain at the recorded base. Isolated base release hashes: ARM64 `060ca13daf481f379fd5e3bb9881dd34364698c87c3c3f7cf895171bd3b5b475`; ARMv7 `b02ec59eecb667da35489d4c345aa6864a74c2ee1cd8eb2a630f4364de5728da`.
- Repaired stale benchmark task names and profile-generator Boolean checks; removed global locale mutations. The new runner restricts targets to the two isolated packages and requires an explicit device. It compiles with explicit `speed` AOT then uses `CompilationMode.Ignore`, preserving prepared data on API 30. The first pilot with Partial compilation reset the disposable validation app; guest/Advanced/V2 Automatic 75% was restored before subsequent checks. Existing configured apps were untouched.
- Fire TV exact-base results, five iterations each, CPU frame P95/P99 in ms: horizontal row 13.09/15.63; vertical rows 15.43/21.17; Settings 34.01/82.84; Details 50.72/78.45; sidebar 23.14/26.28. Cold-start time-to-initial-display median 936.14 ms. All six tests pass. These are baseline measurements, not acceptance; API 30 does not expose frame-overrun timing. See validation/PERFORMANCE.md for compilation and comparison limits.
- Fire TV V2 quality-foundation results, five iterations each, CPU frame P95/P99 in ms: horizontal row 13.98/16.66; vertical rows 23.28/25.67; Settings 34.85/110.02; Details 61.17/110.98; sidebar 25.13/28.85. Cold-start time-to-initial-display median 878.13 ms. All six tests pass. Vertical browsing and transitions need optimization; these results do not meet final performance acceptance. Original 100% and V2 Automatic 75% compare the intended default experiences with different content density.
- Self-authored 24 and 59.94 fps H.264 patterns with two silent AAC tracks and English/Spanish embedded/external subtitles are available through validation/playback_fixture.py. Track metadata, manifest/meta/stream/subtitle routes and bounded/open/suffix HTTP seek ranges were verified locally. The server binds loopback and exposes only its fixed fixture files; ADB reverse serves the isolated app.
- Fire TV isolated V2 playback renders the 24 fps pattern, pauses/resumes, seeks from 0:55 to 1:12, selects Spanish audio and displays the English embedded subtitle. Observed statistics show zero dropped frames/underruns during this short check; this is not a long-duration or passthrough acceptance test. Audio selection and speed-dialog dismissal return focus to Play, recorded for Phase 7 launch-control restoration.
- With isolated-app AFR set to On start/stop, Fire TV switches 4K output from 59.94 to 60 Hz for the 24 fps fixture and restores 59.94 Hz on exit. Opening the audio panel leaves 60 Hz stable. The app capability report exposes only 30/50/59.94/60 Hz, despite additional modes in the system dump. Exact 24 Hz matching is not established; existing player/AFR code is unchanged. Stream selection focus is restored on exit.

