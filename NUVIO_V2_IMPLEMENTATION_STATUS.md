# Nuvio V2 implementation status

## Recovery reference

- Base branch: `nuvio-test`
- Base commit: `45e0984c18460d2a65c5d745999011b4314328eb`
- Initial feature HEAD: `45e0984c18460d2a65c5d745999011b4314328eb`
- Latest recorded feature HEAD before this status checkpoint: `85fd97be545710de692dff4105974c3117a34493`
- First remote baseline/status checkpoint: `cf3225698a6a95d39b03e92c721f2b62bd892dcb`
- Earlier local-only baseline commit: `f57a9c2500127885f87b72d352a8781ee527a914` (same status contents; not a remote ancestor).
- Resolve the checkpoint's own HEAD with `git rev-parse HEAD`; a commit cannot embed its own hash.

## Progress

- Current phase: Phase 0 — blocked on Android build environment
- Completed phases: none
- Both supplied documents have been read completely (413-line Autonomous Work Brief and 3,714-line technical specification v0.4).
- Feature branch created from the verified latest remote base above; no application code changed.

## Baseline validation

- Android baseline command attempted on the unmodified application:
  `./gradlew :app:testFullDebugUnitTest :app:lintFullDebug :app:assembleFullDebug --continue --stacktrace`
- Result: wrapper bootstrap failed before any Gradle task ran. Downloading
  `https://services.gradle.org/distributions/gradle-8.13-bin.zip` raised
  `java.net.SocketException: Network is unreachable`.
- No installed Gradle, Android SDK/adb/emulator, or cached Gradle distribution was found in the inspected standard locations. Java 17 runtime is present; `javac` is not on PATH.
- Android build, lint and unit-test results: **unknown**, not application failures.
- Release-mode build, instrumentation and performance tests: **not run**; same toolchain blocker, plus no connected TV/emulator.
- Release-tooling baseline: `bash -n scripts/generate-release-notes.sh scripts/release-metadata.sh` passed.
- Release-tooling tests: `PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests -v` — **19 passed**.
- Static performance inventory: `MainActivity` already logs janky frames with JankStats; `baselineprofile` has a D-pad profile-generation journey. No runtime P95/P99, jank percentage or startup measurements were obtained.

## Architecture decisions

- Preserve `nuvio-test` playback, repository, profile/session and navigation-domain logic.
- Implement V2 as a device-selectable presentation layer while keeping Original Nuvio available.
- Keep UI scale (geometry) independent from visual quality (rendering budget).

## Known issues

- Android validation is blocked by missing build tooling and restricted dependency-download access. No pre-existing Android source failure has been established.
- Existing PR debug CI targets `dev`, not `nuvio-test`, and covers updater tests rather than the whole test suite. Available GitHub connector actions do not expose workflow dispatch.
- The release workflow is not a substitute for validation: its build modes can create releases, while dry-run only validates release metadata. It has not been dispatched or modified.
- Inherited upstream contribution rules restrict UI/features; this task is explicitly requested by the fork owner. Do not misrepresent the redesign as a critical bug fix or mark incompatible PR-template assertions true.

## Codex handoff

- Select the existing remote `feature/nuvio-v2-ui` branch. Do not recreate it or overwrite its history. The recorded `nuvio-test` base remains the recovery reference.
- Read `NUVIO_V2_AUTONOMOUS_WORK_BRIEF.md` and `Nuvio_V2_UI_UX_Technical_Design_Spec_v0.4.md` completely before application changes. These are unchanged copies of the supplied documents, with normalized filenames.
- The baseline report was committed first; the two source documents were added afterward. Application code is unchanged from the recorded base.
- GitHub branch/document writes succeeded through the connected GitHub integration. The earlier command-line Git push failure did not indicate missing repository permissions.
- The Android toolchain/network observations below describe the earlier Work workspace. Recheck them in the new Codex environment; do not assume it has the same limitations.
- Finish baseline build/lint/tests and practical instrumentation, record results, then implement autonomously according to the brief. Target the final PR at `nuvio-test`; do not merge or publish a release.

## Resume requirements

Provide a build-capable environment with JDK 17, Android SDK platform 36 and required build tools, Gradle 8.13, and authorized access to the repository's dependency repositories (or their complete caches). Alternatively, explicitly authorize a branch-scoped GitHub Actions validation workflow and provide a supported way to execute and retrieve its results.

Then rerun the Android baseline before V2 changes, recording failures against the immutable base SHA. Include `CI_USE_DEBUG_SIGNING=true ./gradlew :app:assembleFullRelease` for a non-production-signed optimized build; never require or expose production signing credentials for validation.

Continue with diagnostics and the Original/V2 preference foundation after the baseline is established. No implementation phase or completion PR is claimed.

## Remaining physical-device validation

- Ugoos AM9 Pro and Fire TV Stick 4K Max logical-canvas calibration on the same TV.
- 2 GB and 4 GB device performance, memory, D-pad, 1080p/4K and AFR/player-overlay validation.
- No test APK exists yet. Exact APK hashes and grouped device procedures must accompany a successfully built diagnostics checkpoint before requesting owner measurements.
