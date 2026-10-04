# IPTV Android fixture tests

This standalone Android project compiles the production IPTV sources and their tests from `app/src` through Gradle Sync tasks. It does not copy source into the repository or launch Nuvio's Application, player, accounts or background services.

The fixture app is `com.nuvio.iptv.validation`, labelled **Nuvio IPTV Validation**, with no launcher activity or network permission. The instrumentation package is `com.nuvio.iptv.validation.test`. Each storage test creates a uniquely named database and Android Keystore alias and cleans up its own data. HTTP integration tests use an in-process interceptor with fake responses; they never contact providers.

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
