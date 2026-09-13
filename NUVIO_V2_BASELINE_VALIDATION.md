# Nuvio V2 baseline validation

Application base: `45e0984c18460d2a65c5d745999011b4314328eb`.

Validated on Windows on 13 September 2026 with Temurin JDK 17.0.20.1, Gradle 8.13, SDK 36 and the installed Android build tools. No application source or build configuration changed for these results. Missing service credentials were left unconfigured. Production signing was not used.

## Results

- `:app:assembleFullDebug`: passed (ARM64 and ARMv7).
- `CI_USE_DEBUG_SIGNING=true :app:assembleFullRelease`: passed, including R8 and release vital lint (ARM64 and ARMv7).
- Full unit suite initially failed to compile because fixtures in five test files lagged current APIs. Constructor arguments, a renamed argument and repository interface stubs were repaired without application changes.
- After those fixture repairs: 1,325 tests, 1,287 passed, 37 failed, one skipped. Failure list below is a comparison baseline, not a claim that every failure is a product defect. Configuration, host-platform behavior and stale expectations need separate triage.
- Full lint: 3,592 errors, 1,835 warnings, 22 hints. Nothing was suppressed or added to a lint baseline file.
- Release-tooling Python suite: 19 passed. Bash syntax validation could not start in this Windows sandbox (MSYS signal pipe error); the earlier Work baseline passed that check.
- No connected ADB devices or installed TV emulator images were available. No runtime frame timing, startup, AFR, focus or paired canvas measurements are claimed.
- D8/R8 reported stale startup-profile classes/methods and distribution across multiple dex files. Baseline Profile regeneration remains required.

Focused rerun after all fixture repairs (including explicit sparse allocation): 100 tests, 99 passed, one existing live-credential test skipped, no failures.

## Reproduction

Set `JAVA_HOME` to JDK 17, `ANDROID_HOME` to the SDK, `ANDROID_USER_HOME` to a writable build-local directory, and `CI_USE_DEBUG_SIGNING=true`. Use an existing development debug key; never a production keystore. Run:

```text
gradlew.bat :app:testFullDebugUnitTest :app:lintFullDebug :app:assembleFullDebug :app:assembleFullRelease -Pkotlin.compiler.execution.strategy=in-process --max-workers=4 --continue --stacktrace
```

In-process Kotlin compilation avoids a host Kotlin-daemon directory permission error. These are environment settings, not committed Gradle configuration changes.

## ARM64 reference APK hashes

- Debug SHA-256: `ecb9ad684ae1dcbc76985f2562f9cb3f0bfc30a720e70475145191a70da38bbb`
- Release SHA-256: `f4ee1cfb04f27be66a5cb2efbeb2d7884753dc07bc8d9c4817c0bcbb39927151`

Both use the recorded application base with the existing version name `0.9.0-beta-nt1`. Test-source repairs do not enter either APK.

## Lint error inventory

- MissingTranslation: 2489
- UnsafeOptInUsageError: 963
- RememberInComposition: 33
- RestrictedApi: 32
- LocalContextGetResourceValueCall: 25
- StringFormatInvalid: 22
- MissingQuantity: 7
- NewApi: 6
- StringFormatMatches: 5
- ResourceType: 4
- UnrememberedGetBackStackEntry: 2
- GestureBackNavigation: 1
- SuspiciousIndentation: 1
- NonObservableLocale: 1
- ContextCastToActivity: 1

## Unit failures before V2

- `androidx.media3.datasource.LocalhostZeroCopyDataSourceTest` — testHttpError404: java.lang.AssertionError: unexpected exception type thrown; expected:<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException> but was:<androidx.media3.datasource.HttpDataSource.HttpDataSourceException>
- `androidx.media3.exoplayer.upstream.DefaultAllocatorTest` — testLateReleasedAllocationsMemoryLeak: java.lang.AssertionError: expected:<0> but was:<196608>
- `com.nuvio.tv.core.debrid.DirectDebridStreamFilterTest` — applies every stream preference category to provided oppenheimer response: java.lang.AssertionError: expected:<22> but was:<8>
- `com.nuvio.tv.core.debrid.DirectDebridStreamFilterTest` — filters minimum quality dv hdr and codec: java.lang.AssertionError: expected:<[10]> but was:<[20, 10, 30, 40]>
- `com.nuvio.tv.core.debrid.DirectDebridStreamFilterTest` — limits and sorts streams by quality and size: java.lang.AssertionError: expected:<[30, 10]> but was:<[30, 10, 20, 40]>
- `com.nuvio.tv.core.debrid.DirectDebridStreamFilterTest` — filters provided torbox response through dto mapper path: java.lang.AssertionError: expected:<7> but was:<6>
- `com.nuvio.tv.core.debrid.DirectDebridStreamFilterTest` — preserves original order by default: java.lang.AssertionError: expected:<[Low, Large, Mid]> but was:<[Large, Mid, Low]>
- `com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicyTest` — non-Amazon device on DV display with DV81 decoder falls through to NATIVE_DV7: java.lang.AssertionError: expected:<NATIVE_DV7> but was:<CONVERT_TO_DV81>
- `com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicyTest` — Samsung device on DV display still falls through to NATIVE_DV7: java.lang.AssertionError: expected:<NATIVE_DV7> but was:<CONVERT_TO_DV81>
- `com.nuvio.tv.core.player.FrameRateUtilsAfrTest` — sparse file vs concatenation layout preserves original atom offsets: java.io.IOException: There is not enough space on the disk
- `com.nuvio.tv.core.player.FrameRateUtilsMkvSparseTracksTest` — sparse Tracks fetch terminates the probe file with a stub Cluster: Probe file must end with a stub Cluster after the sparse Tracks fetch: arrays first differed at element [0]; expected:<31> but was:<0>
- `com.nuvio.tv.core.player.MatroskaAfrProbeTest` — stub Cluster terminates Cluster-less head and resizes Segment: arrays first differed at element [0]; expected:<31> but was:<0>
- `com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolverTest` — discover source forwards advanced movie filters: java.util.NoSuchElementException: Expected at least one element matching the predicate
- `com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolverTest` — list source maps items and pagination: java.util.NoSuchElementException: Expected at least one element matching the predicate
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchPersonDetail falls back CJK filmography titles to English: java.lang.AssertionError
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment falls back TV aggregate credits cast and creator names to English for Polish locale: java.lang.AssertionError
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment falls back CJK movie title to English: java.lang.AssertionError: expected:<Chainsaw Man - The Compilation Movie> but was:<null>
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — company browse requests movie then tv rails when source type is movie: java.lang.AssertionError: expected:<[MOVIE, MOVIE, MOVIE, TV, TV, TV]> but was:<[]>
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment keeps CJK movie title when language is Japanese: java.lang.AssertionError: expected:<チェンソーマン総集篇 後篇> but was:<null>
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment formats ended tv release range: java.lang.AssertionError: expected:<2012-2019> but was:<null>
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchMovieCollection keeps CJK titles when language is Japanese: java.lang.AssertionError: expected:<チェンソーマン シリーズ> but was:<null>
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment maps tmdb ids onto production and network companies: java.lang.AssertionError
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment formats ongoing tv release range: java.lang.AssertionError: expected:<2012-> but was:<null>
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — network browse falls back CJK titles to English: java.util.NoSuchElementException: Collection contains no element matching the predicate.
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchEnrichment falls back Japanese movie cast names to English for Turkish locale: java.lang.AssertionError
- `com.nuvio.tv.core.tmdb.TmdbMetadataServiceTest` — fetchMovieCollection falls back CJK titles to English: java.lang.AssertionError: expected:<Chainsaw Man Collection> but was:<null>
- `com.nuvio.tv.core.tracking.TrackingSourcesTest` — MDBList appears in the picker only when configured and never displaces Trakt or Simkl: java.lang.AssertionError: expected:<[LOCAL]> but was:<[LOCAL, MDBLIST]>
- `com.nuvio.tv.data.local.CollectionsDataStoreSourceMigrationTest` — validation rejects trakt sources without list id: java.lang.AssertionError
- `com.nuvio.tv.data.repository.MDBListWatchedServiceTest` — derivation collects watched movie ids by imdb: java.lang.AssertionError: expected:<[tt11378946]> but was:<[tt11378946, tmdb:936075]>
- `com.nuvio.tv.data.repository.TraktAuthServiceTest` — refresh token 400 clears credentials and prevents another refresh: java.lang.AssertionError: Verification failed: call 1 of 1: TraktApi(#1898).refreshToken(any(), any())) was not called
- `com.nuvio.tv.ui.screens.home.ContinueWatchingAiringRulesTest` — earliestUpcomingEpisodeMs uses next mid-season air time not only next season: java.lang.AssertionError: expected:<1783864800000> but was:<1783900800000>
- `com.nuvio.tv.ui.screens.home.HomeEnrichmentRepositoryBoundaryTest` — a real repository transport failure is retried and then resolves: java.lang.AssertionError: the resolved item should now be cached expected:<true> but was:<false>
- `com.nuvio.tv.ui.screens.player.PlaybackPassthroughSinkStartupTest` — test passthrough configuration arms initial startup compensation: java.lang.AssertionError: Verification failed: call 1 of 1: AudioSink(#2502).handleDiscontinuity()) was not called.
- `com.nuvio.tv.ui.screens.player.PostPlayRecommendationStateTest` — loaded recommendation holds natural completion until overlay evaluation: java.lang.AssertionError
- `com.nuvio.tv.ui.screens.player.PostPlayRecommendationStateTest` — post play recommendations default on and respect the setting: java.lang.AssertionError
- `com.nuvio.tv.ui.screens.player.PostPlayRecommendationStateTest` — post play returns to player only while its window is available: java.lang.AssertionError
- `com.nuvio.tv.ui.screens.player.TrackSelectionInvestigationTest` — testBuildStreamInfoDataWithActiveVideoFormat: java.lang.ClassCastException: class java.lang.Object cannot be cast to class com.nuvio.tv.ui.screens.player.PlaybackTimelineState (java.lang.Object is in module java.base of loader 'bootstrap'; com.nuvio.tv.ui.screens.player.PlaybackTimelineState is in unnamed module of loader 'app')

The sparse-file test attempted to physically allocate nearly 10 GB on Windows. The fixture now explicitly requests a sparse file, retaining its original large-offset assertions. This is tracked separately from playback behavior.
