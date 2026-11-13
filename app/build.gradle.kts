plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

import com.android.build.gradle.internal.tasks.CompileArtProfileTask
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import java.io.File
import java.util.Properties

// Copies the per-API dex metadata (.dm) files AGP builds next to the binary ART profile into the APK's assets, so the
// in-app updater can install the next version together with its profile and have it compiled at install time.
abstract class EmbedUpdateDexMetadataTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dexMetadataDirectory: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun embed() {
        val target = outputDirectory.get().asFile.resolve("updater_dm")
        target.deleteRecursively()
        target.mkdirs()
        val source = dexMetadataDirectory.files.firstOrNull { it.resolve("dex-metadata-map.properties").exists() } ?: return
        val apiToFile = Properties().apply { source.resolve("dex-metadata-map.properties").inputStream().use(::load) }
        // Written by hand instead of Properties.store, which adds a timestamp and would make the APK non-reproducible.
        val indexLines = apiToFile.stringPropertyNames().sortedBy { it.toInt() }.map { api ->
            val relativePath = apiToFile.getProperty(api)
            val fileName = relativePath.substringBefore('/') + ".dm"
            source.resolve(relativePath).copyTo(target.resolve(fileName), overwrite = true)
            "$api=$fileName"
        }
        target.resolve("index.properties").writeText(indexLines.joinToString("\n", postfix = "\n"))
    }
}

fun parseBooleanProperty(value: String?): Boolean {
    val normalized = value?.trim()?.lowercase() ?: return false
    return normalized == "1" || normalized == "true" || normalized == "yes" || normalized == "on"
}

fun resolveProperty(dev: Properties, local: Properties, key: String, fallback: String = ""): String {
    return dev.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: local.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }
        ?: fallback
}

fun buildConfigString(value: String): String {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

fun cmakePath(path: String): String {
    if (path.isBlank()) return ""
    val file = File(path)
    val resolved = if (file.isAbsolute) file else rootProject.file(path)
    return resolved.absolutePath.replace("\\", "/")
}

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        load(localPropertiesFile.inputStream())
    }
}

val devProperties = Properties().apply {
    val devPropertiesFile = rootProject.file("local.dev.properties")
    if (devPropertiesFile.exists()) {
        load(devPropertiesFile.inputStream())
    }
}

val enableDoviNative = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_NATIVE_ENABLED", "true")
)
val doviExtractorHookReady = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_EXTRACTOR_HOOK_READY", "true")
)
val doviEnableRealLink = parseBooleanProperty(
    resolveProperty(devProperties, localProperties, "DOVI_ENABLE_REAL_LINK", "true")
)
if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
    check(enableDoviNative && doviExtractorHookReady && doviEnableRealLink) {
        "Fork release builds require the native Dolby Vision bridge, extractor hook and real libdovi linkage."
    }
}
val enableAmlFelNative = resolveProperty(devProperties, localProperties, "AML_FEL_NATIVE_ENABLED", "true").let(::parseBooleanProperty)
// Fire TV Cube 3 (Gazelle) FEL APK: -PDVFEL_GAZELLE_ENABLED=true builds the libnuvio_dvfel JNI shim and packages
// the prebuilt src/dvfel/jniLibs/armeabi-v7a/libdvfelplay.so. Off by default, so the normal APK carries neither.
val enableDvfelGazelle = (providers.gradleProperty("DVFEL_GAZELLE_ENABLED").orNull
    ?: resolveProperty(devProperties, localProperties, "DVFEL_GAZELLE_ENABLED", "false")).let(::parseBooleanProperty)
// ABIs to build APKs (and native code) for, comma-separated. The normal APK ships armeabi-v7a and arm64-v8a;
// the FEL APK only armeabi-v7a, the one ABI libdvfelplay is built for. Set NUVIO_ABIS=armeabi-v7a in
// local.dev.properties (or -PNUVIO_ABIS=...) for faster local builds.
val apkAbis = (providers.gradleProperty("NUVIO_ABIS").orNull
    ?: resolveProperty(devProperties, localProperties, "NUVIO_ABIS", if (enableDvfelGazelle) "armeabi-v7a" else "armeabi-v7a,arm64-v8a"))
    .split(',').map { it.trim() }.filter { it.isNotEmpty() }
val doviStaticLibPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_STATIC_LIB")
val doviIncludeDirPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_INCLUDE_DIR")
val doviPrebuiltRootPath = resolveProperty(devProperties, localProperties, "DOVI_LIBDOVI_PREBUILT_ROOT")
val sponsorNames = resolveProperty(devProperties, localProperties, "SPONSOR_NAMES", "ragmehos.")

fun env(name: String): String? = providers.environmentVariable(name).orNull

fun truthy(value: String?): Boolean {
    return value.equals("true", ignoreCase = true) ||
        value.equals("1", ignoreCase = true) ||
        value.equals("yes", ignoreCase = true)
}

val buildingAppBundle = gradle.startParameter.taskNames.any { it.contains("bundle", ignoreCase = true) }
val useDebugReleaseSigning = env("CI_USE_DEBUG_SIGNING").equals("true", ignoreCase = true)
val useLocalFfmpegDecoder = truthy(
    providers.gradleProperty("useLocalFfmpegDecoder").orNull
        ?: env("USE_LOCAL_FFMPEG_DECODER")
        ?: localProperties.getProperty("USE_LOCAL_FFMPEG_DECODER")
)
val releaseStoreFilePath = env("NUVIO_RELEASE_STORE_FILE")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_FILE")
val releaseKeyAliasValue = env("NUVIO_RELEASE_KEY_ALIAS")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_ALIAS", "nuviotv")
val releaseKeyPasswordValue = env("NUVIO_RELEASE_KEY_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_KEY_PASSWORD", "815787")
val releaseStorePasswordValue = env("NUVIO_RELEASE_STORE_PASSWORD")
    ?: localProperties.getProperty("NUVIO_RELEASE_STORE_PASSWORD", "815787")

android {
    namespace = "com.nuvio.tv"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.nuvio.tv.test"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 24
        targetSdk = 36
        versionCode = providers.gradleProperty("arcVersionCode").orNull?.toIntOrNull() ?: 1568
        versionName = providers.gradleProperty("arcVersionName").orNull ?: "1.1.0-nt.9.2"
        // Optional -PnuvioAppIdSuffix=.name installs a local test build next to the main app.
        providers.gradleProperty("nuvioAppIdSuffix").orNull?.takeIf { it.isNotBlank() }?.let {
            applicationIdSuffix = it
        }

        val gitCommit = providers.gradleProperty("arcGitCommit").orNull ?: runCatching {
            providers.exec { commandLine("git", "rev-parse", "--short=9", "HEAD") }.standardOutput.asText.get().trim()
        }.getOrDefault("")
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${localProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
        buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
        buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
        buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
        buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_ID", "\"${localProperties.getProperty("TRAKT_CLIENT_ID", "")}\"")
        buildConfigField("String", "TRAKT_CLIENT_SECRET", "\"${localProperties.getProperty("TRAKT_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "TRAKT_API_URL", "\"${localProperties.getProperty("TRAKT_API_URL", "https://api.trakt.tv/")}\"")
        buildConfigField("String", "TRAKT_REDIRECT_URI", "\"${localProperties.getProperty("TRAKT_REDIRECT_URI", "urn:ietf:wg:oauth:2.0:oob")}\"")
        buildConfigField("String", "SIMKL_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "SIMKL_CLIENT_ID")))
        buildConfigField("String", "SIMKL_APP_NAME", buildConfigString(resolveProperty(devProperties, localProperties, "SIMKL_APP_NAME", "nuvio")))
        buildConfigField("String", "MDBLIST_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "MDBLIST_CLIENT_ID")))
        buildConfigField("String", "WETRAKR_CLIENT_ID", buildConfigString(resolveProperty(devProperties, localProperties, "WETRAKR_CLIENT_ID")))
        buildConfigField("String", "TMDB_API_KEY", "\"${localProperties.getProperty("TMDB_API_KEY", "")}\"")
        buildConfigField("String", "TVDB_API_KEY", "\"${localProperties.getProperty("TVDB_API_KEY", "")}\"")
        buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
        buildConfigField("String", "DEVICE_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("DEVICE_LOGIN_WEB_BASE_URL", "https://nuvio.tv/link")}\"")
        buildConfigField("boolean", "SCOUT_ENABLED", localProperties.getProperty("SCOUT_ENABLED", "false").toBoolean().toString())
        buildConfigField("boolean", "DOVI_NATIVE_ENABLED", enableDoviNative.toString())
        buildConfigField("boolean", "DOVI_EXTRACTOR_HOOK_READY", doviExtractorHookReady.toString())
        buildConfigField("boolean", "AML_FEL_NATIVE_ENABLED", enableAmlFelNative.toString())
        buildConfigField("boolean", "DVFEL_GAZELLE_ENABLED", enableDvfelGazelle.toString())
        if (enableDvfelGazelle) {
            // Build metadata, ignored by the updater's version comparison: 1.1.0-arc.2+fel.
            versionNameSuffix = "+fel"
        }
        // The updater installs the release asset of the same kind: app-full[-fel]-<abi>-release.apk.
        buildConfigField("String", "UPDATE_APK_PREFIX", if (enableDvfelGazelle) "\"app-full-fel\"" else "\"app-full\"")
        // libnuviothumb is always built; dovi_bridge, aml_fel and nuvio_dvfel only when enabled.
        externalNativeBuild {
            cmake {
                arguments(
                    "-DNUVIO_BUILD_DOVI=${if (enableDoviNative) "ON" else "OFF"}"
                )
            }
        }
        if (enableDoviNative || enableAmlFelNative || enableDvfelGazelle) {
            externalNativeBuild {
                cmake {
                    arguments(
                        "-DAML_FEL_NATIVE_ENABLED=${if (enableAmlFelNative) "ON" else "OFF"}",
                        "-DDVFEL_GAZELLE_ENABLED=${if (enableDvfelGazelle) "ON" else "OFF"}",
                        "-DDOVI_ENABLE_LIBDOVI=${if (doviEnableRealLink) "ON" else "OFF"}",
                        "-DDOVI_LIBDOVI_STATIC_LIB=${cmakePath(doviStaticLibPath)}",
                        "-DDOVI_LIBDOVI_INCLUDE_DIR=${cmakePath(doviIncludeDirPath)}",
                        "-DDOVI_LIBDOVI_PREBUILT_ROOT=${cmakePath(doviPrebuiltRootPath)}"
                    )
                }
            }
        }
        buildConfigField("String", "SUPPORTERS_API_BASE_URL", buildConfigString(localProperties.getProperty("SUPPORTERS_API_BASE_URL", "https://nuvio.tv/")))
        buildConfigField("String", "SUPPORT_URL", buildConfigString(localProperties.getProperty("SUPPORT_URL", "https://nuvio.tv/support")))
        buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
        buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", "")}\"")
        buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", buildConfigString(localProperties.getProperty("PLAYBACK_REPORTS_BASE_URL", "")))
        buildConfigField("String", "REPORTS_SIGNING_KEY", buildConfigString(localProperties.getProperty("REPORTS_SIGNING_KEY", "")))
        buildConfigField("String", "PREMIUMIZE_CLIENT_ID", "\"${localProperties.getProperty("PREMIUMIZE_CLIENT_ID", "")}\"")
        buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))

        // One beta stream for this fork, including GitHub prereleases.
        buildConfigField("String", "GITHUB_OWNER", "\"ysosrs123\"")
        buildConfigField("String", "GITHUB_REPO", "\"NuvioTV-Fork\"")
        buildConfigField("String", "GITHUB_REPO_NEW_NAME", "\"Nuvio-Arc\"")
        buildConfigField("boolean", "UPDATE_CHECK_ENABLED", "true")
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "FEATURE_PLUGINS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_IN_APP_UPDATES_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_IN_APP_TRAILERS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_EXTERNAL_TRAILERS_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_EXTERNAL_PLAYBACK_KEEP_ALIVE_ENABLED", "true")
            buildConfigField("boolean", "FEATURE_CUSTOM_SERVER_CONNECTIONS_ENABLED", "true")
        }

    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    signingConfigs {
        create("release") {
            keyAlias = releaseKeyAliasValue
            keyPassword = releaseKeyPasswordValue
            storeFile = releaseStoreFilePath?.let(::file) ?: file("../nuviotv.jks")
            storePassword = releaseStorePasswordValue
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = parseBooleanProperty(providers.gradleProperty("debuggable").orNull)
            isMinifyEnabled = false

            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")

            // Dev environment (from local.dev.properties)
            buildConfigField("String", "SUPABASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_URL")))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_ANON_KEY")))
            buildConfigField("String", "SUPABASE_FALLBACK_URL", buildConfigString(resolveProperty(devProperties, localProperties, "NUVIO_SUPABASE_FALLBACK_URL")))
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
            buildConfigField("String", "DEVICE_LOGIN_WEB_BASE_URL", "\"${devProperties.getProperty("DEVICE_LOGIN_WEB_BASE_URL", "https://nuvio.tv/link")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${devProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${devProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${devProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${devProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${devProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "SUPPORTERS_API_BASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "SUPPORTERS_API_BASE_URL", "https://nuvio.tv/")))
            buildConfigField("String", "SUPPORT_URL", buildConfigString(resolveProperty(devProperties, localProperties, "SUPPORT_URL", "https://nuvio.tv/support")))
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${devProperties.getProperty("AVATAR_PUBLIC_BASE_URL", localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", ""))}\"")
            buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${devProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", ""))}\"")
            buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", buildConfigString(resolveProperty(devProperties, localProperties, "PLAYBACK_REPORTS_BASE_URL")))
            buildConfigField("String", "REPORTS_SIGNING_KEY", buildConfigString(resolveProperty(devProperties, localProperties, "REPORTS_SIGNING_KEY")))
            buildConfigField("String", "PREMIUMIZE_CLIENT_ID", "\"${devProperties.getProperty("PREMIUMIZE_CLIENT_ID", localProperties.getProperty("PREMIUMIZE_CLIENT_ID", ""))}\"")
            buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useDebugReleaseSigning) {
                signingConfigs.getByName("debug")
            } else {
                signingConfigs.getByName("release")
            }

            buildConfigField("boolean", "IS_DEBUG_BUILD", "false")

            // Production environment (from local.properties)
            buildConfigField("String", "SUPABASE_URL", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_URL", "")))
            buildConfigField("String", "SUPABASE_ANON_KEY", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_ANON_KEY", "")))
            buildConfigField("String", "SUPABASE_FALLBACK_URL", buildConfigString(localProperties.getProperty("NUVIO_SUPABASE_FALLBACK_URL", "")))
            buildConfigField("String", "TV_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("TV_LOGIN_WEB_BASE_URL", "https://nuvio.tv/tv-login")}\"")
            buildConfigField("String", "DEVICE_LOGIN_WEB_BASE_URL", "\"${localProperties.getProperty("DEVICE_LOGIN_WEB_BASE_URL", "https://nuvio.tv/link")}\"")
            buildConfigField("String", "PARENTAL_GUIDE_API_URL", "\"${localProperties.getProperty("PARENTAL_GUIDE_API_URL", "")}\"")
            buildConfigField("String", "INTRODB_API_URL", "\"${localProperties.getProperty("INTRODB_API_URL", "")}\"")
            buildConfigField("String", "TRAILER_API_URL", "\"${localProperties.getProperty("TRAILER_API_URL", "")}\"")
            buildConfigField("String", "IMDB_RATINGS_API_BASE_URL", "\"${localProperties.getProperty("IMDB_RATINGS_API_BASE_URL", "")}\"")
            buildConfigField("String", "IMDB_TAPFRAME_API_BASE_URL", "\"${localProperties.getProperty("IMDB_TAPFRAME_API_BASE_URL", "")}\"")
            buildConfigField("String", "SUPPORTERS_API_BASE_URL", buildConfigString(localProperties.getProperty("SUPPORTERS_API_BASE_URL", "https://nuvio.tv/")))
            buildConfigField("String", "SUPPORT_URL", buildConfigString(localProperties.getProperty("SUPPORT_URL", "https://nuvio.tv/support")))
            buildConfigField("String", "AVATAR_PUBLIC_BASE_URL", "\"${localProperties.getProperty("AVATAR_PUBLIC_BASE_URL", "")}\"")
            buildConfigField("String", "UNIQUE_CONTRIBUTIONS_BASE_URL", "\"${localProperties.getProperty("UNIQUE_CONTRIBUTIONS_BASE_URL", "")}\"")
            buildConfigField("String", "PLAYBACK_REPORTS_BASE_URL", buildConfigString(localProperties.getProperty("PLAYBACK_REPORTS_BASE_URL", "")))
            buildConfigField("String", "REPORTS_SIGNING_KEY", buildConfigString(localProperties.getProperty("REPORTS_SIGNING_KEY", "")))
            buildConfigField("String", "PREMIUMIZE_CLIENT_ID", "\"${localProperties.getProperty("PREMIUMIZE_CLIENT_ID", "")}\"")
            buildConfigField("String", "SPONSOR_NAMES", buildConfigString(sponsorNames))
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "IS_DEBUG_BUILD", "true")
            applicationIdSuffix = ".debug"
            matchingFallbacks += "release"
        }
    }

    splits {
        abi {
            isEnable = !buildingAppBundle
            reset()
            include(*apkAbis.toTypedArray())
            isUniversalApk = false
        }
    }

    bundle {
        language {
            // Keep all string resources in the
            // base install so Play Store installs can switch languages at runtime.
            // https://developer.android.com/guide/app-bundle/configure-base
            enableSplit = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("src/main/jniLibs")
            if (enableDvfelGazelle) jniLibs.srcDir("src/dvfel/jniLibs")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            // Keep one consistent native set across dependencies.
            pickFirsts += listOf(
                "lib/*/libc++_shared.so",
                "lib/*/libavcodec.so",
                "lib/*/libavdevice.so",
                "lib/*/libavfilter.so",
                "lib/*/libavformat.so",
                "lib/*/libavutil.so",
                "lib/*/libswscale.so",
                "lib/*/libswresample.so"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    // Only the full flavor ships the in-app updater.
    onVariants(selector().withFlavor("distribution" to "full")) { variant ->
        // AGP makes no ART profile for debuggable variants, and ART ignores .dm files for them anyway.
        if (variant.debuggable) return@onVariants
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val embedTask = tasks.register<EmbedUpdateDexMetadataTask>("embed${variantName}UpdateDexMetadata") {
            dexMetadataDirectory.from(provider {
                tasks.withType<CompileArtProfileTask>()
                    .matching { it.name == "compile${variantName}ArtProfile" }
                    .map { it.dexMetadataDirectory }
            })
        }
        variant.sources.assets?.addGeneratedSourceDirectory(embedTask, EmbedUpdateDexMetadataTask::outputDirectory)
    }
}

afterEvaluate {
    tasks.withType<L8DexDesugarLibTask>().configureEach {
        if (name.endsWith("AndroidTest")) {
            keepRulesConfigurations.add("-keep class j\$.** { *; }")
        }
    }
}

composeCompiler {
    // Enable Compose compiler metrics for performance analysis
    metricsDestination = layout.buildDirectory.dir("compose_metrics")
    reportsDestination = layout.buildDirectory.dir("compose_reports")
    stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability_config.conf"))
}

// Globally exclude stock media3 modules — replaced by local :nuvio-exoplayer-engine module
configurations.all {
    exclude(group = "androidx.media3", module = "media3-exoplayer")
    exclude(group = "androidx.media3", module = "media3-common")
    exclude(group = "androidx.media3", module = "media3-datasource")
    exclude(group = "androidx.media3", module = "media3-datasource-okhttp")
    exclude(group = "androidx.media3", module = "media3-exoplayer-hls")
    exclude(group = "androidx.media3", module = "media3-extractor")
}

baselineProfile {
    automaticGenerationDuringBuild = false
    dexLayoutOptimization = true
    saveInSrc = true
    mergeIntoMain = true
    baselineProfileOutputDir = "generated/baselineProfiles"
    // Keep in step with KEPT_PACKAGES in BaselineProfileGenerator.kt.
    filter {
        include("com.nuvio.tv.**")
        include("androidx.**")
        include("kotlin.**")
        include("kotlinx.coroutines.**")
        include("okhttp3.**")
        include("okio.**")
        include("retrofit2.**")
        include("com.squareup.moshi.**")
        include("coil3.**")
        include("dagger.**")
    }
}


dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")

    // Source-retention nullness annotations (MonotonicNonNull / RequiresNonNull /
    // EnsuresNonNull) used by the vendored Matroska extractor in
    // com.nuvio.tv.core.player.dvmkv. Media3 keeps these compileOnly in its own
    // build, so they aren't on our classpath via the prebuilt AARs.
    compileOnly("org.checkerframework:checker-qual:3.43.0")

    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.core.ktx)
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.profileinstaller)
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.tv.material)
    implementation(libs.androidx.tvprovider)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("androidx.activity:activity-compose:1.11.0")

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.moshi)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.moshi)
    ksp(libs.moshi.codegen)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.coil.network.okhttp)
    implementation(libs.coil.network.cache.control)

    // Navigation
    implementation(libs.navigation.compose)

    // DataStore
    implementation(libs.datastore.preferences)

    // ViewModel
    implementation(libs.lifecycle.viewmodel.compose)

    // Media3 — remaining stock modules from Maven (not forked)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.decoder)
    implementation(libs.media3.session)
    implementation(libs.media3.container)
    implementation("androidx.media3:media3-effect:1.8.0")

    // Transitive dependencies required by forked local AARs (not bundled in AARs):
    // - Guava: needed by lib-common (ImmutableList/ImmutableSet in Tracks, Player API)
    // - media3-database: needed by lib-datasource (cache/storage layer)
    // - annotation-experimental: needed by lib-common (OptIn annotations)
    implementation("com.google.guava:guava:33.3.1-android")
    implementation("androidx.media3:media3-database:1.8.0")
    implementation("androidx.annotation:annotation-experimental:1.3.1")

    // Other libraries pull concurrent-futures in transitively; this pins its version.
    implementation("androidx.concurrent:concurrent-futures:1.2.0")

    // Nuvio Engine local AARs (replaces lib-exoplayer, lib-common, lib-datasource, lib-datasource-okhttp, lib-exoplayer-hls, lib-extractor)
    implementation(files(
        "libs/lib-common-release.aar",
        "libs/lib-datasource-release.aar",
        "libs/lib-datasource-okhttp-release.aar",
        "libs/lib-exoplayer-release.aar",
        "libs/lib-exoplayer-hls-release.aar",
        "libs/lib-extractor-release.aar"
    ))
    implementation(libs.media3.ui)

    // Local decoder AAR (AV1)
    implementation(files(
        "libs/lib-decoder-av1-release.aar"
    ))
    implementation(files("libs/lib-nuvio-engine-android-0.1.6.aar"))
    if (useLocalFfmpegDecoder) {
        implementation(project(":ffmpeg-decoder-downmix"))
    } else {
        implementation(files("libs/lib-decoder-ffmpeg-release.aar"))
    }

    // libass - atlas-based subtitle rendering (from NuvioMedia/nuvio-libass)
    implementation(files("libs/nuvio-libass-release.aar"))
    implementation("androidx.annotation:annotation-experimental:1.5.1")
    // Local nextlib-mediainfo fork (static FFmpeg; no libav*.so in final AAR)
    implementation(files("libs/nextlib-mediainfo-local.aar"))
    implementation("io.github.abdallahmehiz:mpv-android-lib:0.1.12")
    implementation("dev.chrisbanes.haze:haze-android:1.7.2") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
    }

    implementation(libs.gson)

    add("fullImplementation", files("libs/quickjs-kt-android-1.0.5-nuvio.aar"))
    add("fullImplementation", libs.jsoup)
    add("fullImplementation", "com.fasterxml.jackson.core:jackson-databind:2.17.0")
    add("fullImplementation", "com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")
    add("fullImplementation", libs.nicehttp)
    add("fullImplementation", libs.conscrypt.android)
    add("fullImplementation", "com.github.recloudstream.cloudstream:library:${libs.versions.cloudstream.get()}") {
        exclude(group = "org.mozilla", module = "rhino")
        exclude(group = "com.github.AmarullisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX", module = "newpipeextractor")
        exclude(group = "com.github.AmaryllisVFX.newpipeextractor")
        exclude(group = "info.debatty", module = "java-string-similarity")
    }

    // Markdown rendering
    implementation(libs.markdown.renderer.m3)

    add("fullImplementation", libs.crypto.js)
    // QR code + local server for addon management
    implementation(libs.nanohttpd)
    implementation(libs.zxing.core)


    // Supabase
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.postgrest)
    implementation(libs.supabase.storage)
    implementation(libs.supabase.realtime)
    implementation(libs.ktor.client.okhttp)

    // Kotlinx Serialization
    implementation(libs.kotlinx.serialization.json)

    // Performance profiling
    implementation("androidx.metrics:metrics-performance:1.0.0-rc01")  // JankStats
    debugImplementation("androidx.compose.runtime:runtime-tracing")
    if (providers.gradleProperty("detailTracing").orNull == "true") {
        add("releaseImplementation", "androidx.compose.runtime:runtime-tracing")
        add("releaseImplementation", "androidx.tracing:tracing-perfetto:1.0.0")
        add("releaseImplementation", "androidx.tracing:tracing-perfetto-binary:1.0.0")
    }

    add("fullImplementation", "org.webjars.npm:crypto-js:4.2.0")

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
