# Release performance procedure

Use the separate validation packages described in README.md. The benchmark runner accepts only `com.nuvio.tv.v2.validation` and `com.nuvio.tv.v2.baseline`; it never installs the target application. Prepare guest onboarding, Advanced settings and populated Home before running. Confirm the intended Original/V2, scale and quality choices before each run. Keep the TV on the selected device at approximately 60 Hz. The tests check the current display mode and do not change it.

```powershell
python scripts/perf_baseline.py benchmark --serial DEVICE:5555 --package com.nuvio.tv.v2.validation --iterations 5 --output PATH_TO_NEW_RESULT_DIRECTORY
```

Set `JAVA_HOME` to JDK 17, `ANDROID_HOME` to the Android SDK and use the existing development signing setup. `--skip-build` uses the existing benchmark APK. `--journey horizontalRow` runs one pilot journey. The runner builds `:baselineprofile:assembleBenchmarkRelease`, installs only that self-instrumenting test APK, and explicitly selects one device. The old nonexistent `:benchmark` task names are no longer used.

The runner compiles the target using `cmd package compile -m speed -f` and uses `CompilationMode.Ignore` in the benchmark. This is an explicit full-AOT comparison condition, not an assertion about normal installation/profile compilation. It avoids Macrobenchmark's pre-Android-14 reinstall/reset behavior, which erases prepared app data. See [Android's compilation and state guidance](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview). Do not substitute the default compilation mode on the Fire TV with prepared data.

Journeys cover cold Activity startup, horizontal poster navigation, movement between rows, sidebar expansion/collapse, Details entry/exit and Settings categories. Each asserts that its expected screen is reached. Cold-start timing describes the library's startup metric; the additional wait for populated Home does not itself establish time-to-fully-drawn. Account/profile landing, search and player panels require additional journeys as those V2 screens are implemented.

Record the exact target APK SHA-256, commit, device firmware/API, raw window/density, effective scale, quality, content, cache state and output mode. Compare devices separately. Fire TV Original at 100% and V2 Automatic at 75% have different visible content density; label that comparison as the intended default experience, not identical geometry. Ugoos Original and V2 Automatic both use 100% and provide the matching-geometry comparison.

The runner preserves JSON and Perfetto traces from each invocation in a unique remote directory, copies them to the requested output and captures post-run memory. Failed journeys remain failures even when other journeys produce data. Do not interpret missing/failed metrics as zero. Inspect frame P95/P99, trace outliers and memory; do not average percentile values into a new claimed percentile.

`frameDurationCpuMs` includes UI and render-thread CPU frame production. `frameOverrunMs` is available only on API 31+, so the API 30 Fire TV cannot provide that deadline metric. Neither the diagnostic ring nor CPU frame duration alone proves GPU or end-to-end deadline compliance. See [FrameTimingMetric definitions](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics).

Profile generation uses `generate-profile` on API 34+ and an explicitly selected isolated package. It never changes the device locale. Generated profile collection and integration must be validated against the correct matching source variant before release acceptance. No adaptive quality governor is enabled by this foundation.
