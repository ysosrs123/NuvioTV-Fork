plugins {
    id("com.android.application") version "8.13.2"
    id("org.jetbrains.kotlin.android") version "2.3.0"
}

// Small isolated fixture app, compiling the actual production sources. No player UI/native player libraries,
// accounts, activities, INTERNET permission or application startup services from the full app.
val productionSources = tasks.register<Sync>("syncProductionSources") {
    from("../../app/src/main/java") {
        include("com/nuvio/tv/core/iptv/**", "com/nuvio/tv/core/player/thumbnail/ThumbSourcePolicy.kt",
            "com/nuvio/tv/data/iptv/**")
    }
    into(layout.buildDirectory.dir("generated/fixture-main"))
}
val fixtureTests = tasks.register<Sync>("syncFixtureTests") {
    from("../../app/src/androidTest/java") { include("com/nuvio/tv/data/iptv/**") }
    from("../../app/src/test/java") { include("com/nuvio/tv/core/iptv/**") }
    into(layout.buildDirectory.dir("generated/fixture-tests"))
}
android {
    namespace = "com.nuvio.iptv.validation"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.nuvio.iptv.validation"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("main") {
            java.srcDir(layout.buildDirectory.dir("generated/fixture-main"))
        }
        getByName("androidTest") {
            java.srcDir(layout.buildDirectory.dir("generated/fixture-tests"))
            resources.srcDir("../../app/src/test/resources")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
tasks.named("preBuild") { dependsOn(productionSources, fixtureTests) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Exact shipped Java common/extractor/period/data-source APIs; no UI or native player libraries.
    implementation(files("../../app/libs/lib-common-release.aar", "../../app/libs/lib-extractor-release.aar",
        "../../app/libs/lib-exoplayer-release.aar", "../../app/libs/lib-datasource-release.aar"))
    implementation("androidx.media3:media3-decoder:1.8.0") { isTransitive = false }
    implementation("androidx.media3:media3-container:1.8.0") { isTransitive = false }
    implementation("com.google.guava:guava:33.3.1-android")
    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
