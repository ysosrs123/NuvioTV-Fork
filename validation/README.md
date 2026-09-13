# Nuvio V2 device calibration

This checkpoint implements the appearance/scale foundation. The broader V2 screens and visual quality system are still pending. Spec v0.4 Phase 2 requires Ugoos AM9 Pro and Fire TV Stick 4K Max testing on the same TV before proceeding.

## Isolated installation

Use JDK 17 and Android SDK 36. Build the `full` flavor with the optional init script:

```powershell
$env:CI_USE_DEBUG_SIGNING = 'true'
.\gradlew.bat :app:assembleFullRelease --init-script validation/isolated-ui.init.gradle
```

This produces development-signed, optimized APKs with package `com.nuvio.tv.v2.validation` and launcher label **Nuvio V2 Validation**. Normal builds retain their existing identity. It does not copy accounts, add-ons, profile preferences or credentials from the installed app. Never install a regular build over the owner's configured app as part of this validation.

Check `adb shell getprop ro.product.cpu.abilist` and choose ARM64 or ARMv7 accordingly. A Fire TV's OS may be 32-bit even when its processor supports 64-bit. Install the matching APK with `adb -s DEVICE install -r APK_PATH`. Open **Nuvio V2 Validation**. The default interface is Original Nuvio.

The local validation builds have no private service configuration. The existing sign-in screen can therefore show an unavailable QR code; use **Continue without account** for the geometry checks. Account integration and representative populated-content benchmarks require a configured validation environment.

## Grouped comparison

Use the same TV input settings, picture mode, overscan setting and viewing distance for both devices. Record output mode separately from Android window size/density. Do not use `wm size` or `wm density` overrides to manufacture matching results.

1. Record model, firmware/API, RAM and ABI. Open Settings → Advanced → Device UI Diagnostics. Capture the full report with Original at 100%.
2. Open Appearance → Interface experience → Nuvio V2. Choose Automatic scale and zero fine-tune. Capture diagnostics again, then the same Home row and Appearance screen on both devices. Use matching content, row position and focus target where possible.
3. Compare visible card counts, text size, row spacing and focus reachability. Expected: a similar effective dp canvas and readable density on both devices, without clipping. The paired check on 2026-09-13 matched all 28 labeled Home elements at the 1280 × 720 dp reference (Ugoos 100%, Fire TV 75%).
4. Change only the HDMI output between 1080p/60 and 4K/60 when supported. Capture diagnostics and matching screens. Expected: the scale remains unchanged if the actual app window and base density remain unchanged. A true window/density change may resolve a new scale outside playback.
5. Test automatic fine-tune at -10, 0 and +10; then Manual at 75, 100 and 115. Confirm persistence after force-stop/relaunch and verify that changing color theme or visual style does not alter the resolved scale.
6. Switch back to Original. Confirm its saved scale and AMOLED settings return. Migration runs at app startup, before the first appearance screen. To check an upgrade, first install a pre-foundation build with this same isolated package ID, save Original at 95%, then upgrade to this checkpoint; repeat with explicitly saved 100% on another disposable installation. V2 should start in Manual with that saved value. A new installation with no explicit old value should start Automatic. Unit tests cover these cases; the upgrade procedure requires a matching pre-foundation APK.
7. With locally configured legal test content, start playback that triggers AFR, open/close controls and subtitles, and return to Home. Expected: no UI scale change during playback or the display-mode transition; pending geometry changes apply after leaving the player. Record any focus loss, clipping, crash or playback regression.

## Evidence and performance limits

Return the APK SHA-256, device/output settings, diagnostics before/after and screenshots for each device. Include the exact failing step and logcat excerpt for a defect. Diagnostic reports are emitted under `NuvioUiDiagnostics` without account details.

The bounded diagnostics ring describes recent mixed-screen UI-thread timing. It is not GPU timing and is not a controlled performance benchmark. Debug timing must not be used for release acceptance. The later benchmark phase must run optimized 60 Hz journeys with representative populated Home/Search/Details/Settings/profile/player states, capture P95/P99 and memory, and compare with the recorded base before enabling an adaptive quality governor.

Localization resources for the new settings need native-speaker review during final polish.
