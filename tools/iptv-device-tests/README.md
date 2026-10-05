# IPTV Android fixture tests

This standalone Android project compiles the production IPTV sources and their tests from `app/src` through Gradle Sync tasks. It does not copy source into the repository or launch Nuvio's Application, player, accounts or background services.

The fixture app is `com.nuvio.iptv.validation`, labelled **Nuvio IPTV Validation**, with no launcher activity or INTERNET permission. The instrumentation package is `com.nuvio.iptv.validation.test`. Each storage test creates a uniquely named database and Android Keystore alias and cleans up its own data. HTTP integration tests use an in-process interceptor with fake responses; they never contact providers.

From the repository root, using the existing Android SDK and JDK:

```powershell
./gradlew.bat -p tools/iptv-device-tests assembleDebug assembleDebugAndroidTest --offline --no-daemon
$adb = "$env:ANDROID_HOME/platform-tools/adb.exe"
& $adb -s 192.168.10.60:5555 install -r tools/iptv-device-tests/build/outputs/apk/debug/NuvioIptvDeviceTests-debug.apk
& $adb -s 192.168.10.60:5555 install -r tools/iptv-device-tests/build/outputs/apk/androidTest/debug/NuvioIptvDeviceTests-debug-androidTest.apk
& $adb -s 192.168.10.60:5555 shell am instrument -w com.nuvio.iptv.validation.test/androidx.test.runner.AndroidJUnitRunner
```

Use the explicitly authorised device serial. The first dependency resolution may need an online Gradle run. Check the final JUnit `OK (...)` result: `adb shell am instrument` can exit with code zero even when tests fail. The fixture tests exercise real Android SQLite, Android Keystore and the platform XML parser, plus the pure core suite. They do not establish playback, recording, process-death recovery, provider capacity or storage performance at a full catalogue's size.

The capacity test uses SQLite's logical page-count limit to trigger `SQLITE_FULL`, rather than filling device storage. The DB size setting must be applied to the primary writer connection. SQLite may automatically roll back on `SQLITE_FULL`; the repository preserves that original exception if Android's cleanup then reports that no transaction remains.

Remove only these test packages when finished:

```powershell
& $adb -s 192.168.10.60:5555 uninstall com.nuvio.iptv.validation.test
& $adb -s 192.168.10.60:5555 uninstall com.nuvio.iptv.validation
```

## Optional XMLTV document-picker fixture

The instrumentation APK also exposes a temporary OPEN_DOCUMENT handler labelled **XMLTV test fixture**, with a single **Use synthetic XMLTV fixture** button. It returns only its own generated XMLTV content URI with read/persistable grants. It never lists or reads personal device files. This can validate the prototype's picker/save/refresh/cold-restart path on TV firmware without DocumentsUI. It is test infrastructure, not a shipping picker or proof of USB/document-provider compatibility. Remove the instrumentation and target test packages after checks. Uninstalling the provider invalidates access; last-good imported guide data should remain.

## Controlled capture media and decoder entry checks

The TS continuation uses the exact `app/libs/lib-common-release.aar` and
`lib-extractor-release.aar`, the existing Media3 container 1.8.0 and Guava 33.3.1
in the isolated harness. It does not load Nuvio's application, player services or
native player libraries and does not upgrade dependencies. The common AAR adds
ACCESS_NETWORK_STATE; there is still no INTERNET permission. The instrumentation
APK retains only REORDER_TASKS. Inspect final packaged permissions with aapt.

`app/src/test/resources/iptv-ts` holds original synthetic media. The pure inspector
suite uses the same bytes on JVM and Android. `TsCaptureDecodeAndroidTest` checks
fresh headless Android codecs, the local Media3 extraction bridge, changed-byte
rejection, and real HLS-source/store/live-reader/inspection/extraction/codec flow.
HTTP responses are in-process fixtures, with no socket/server/reverse/provider.
These are overlapping backend tests, not visible playback or audibility checks.

`validate_capture_media.py --report <json>` independently probes and strictly
decodes the committed segments with FFprobe/FFmpeg. `--generate <empty-directory>`
reproduces the synthetic inputs without overwriting the committed fixtures.
The AM9 platform extractor and unadapted shipped Media3 HLS extractor omitted the
last video sample; the hash-bound local bridge restores that sample for the narrow
inspected profile. See `validation/IPTV-TS-ENTRY-DESIGN.md` and its evidence report.
