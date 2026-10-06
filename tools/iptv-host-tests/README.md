# IPTV host tests

Runs the IPTV core and data-layer JVM tests, and compile-checks the IPTV device
tests, on a machine without the Android SDK:

```
python3 tools/iptv-host-tests/run.py                 # core, data, androidtest
python3 tools/iptv-host-tests/run.py core
python3 tools/iptv-host-tests/run.py ui app/src/main/java/com/nuvio/tv/ui/screens/iptv/IptvLivePlayback.kt
```

The first run downloads pinned jars from Maven Central (including Robolectric's
android-all, about 200 MB) and the Media3 1.8.0 decoder and container sources from
GitHub into `build/iptv-host-tests` (override with `IPTV_HOST_WORK`). Later runs reuse
them.

`src/Mockable.java` turns android-all into a default-value `android.jar`, matching
`unitTests.isReturnDefaultValues`. `src/shims` provides pure-Java `SparseArray`,
`Pair` and `TextUtils` so Media3's TS extractor works on the JVM. `src/stubs` holds
annotation and test-runner stubs that are only needed to compile.

This is a supplement, not a replacement: the Gradle build, Compose/Hilt UI code and
device tests still need the Android SDK and the AM9.
