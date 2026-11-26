// The optional on-device AI pack: the on-device LLM engines (LiteRT-LM, llama.cpp), the wake-word runtime and the
// wake-word models, built as a signed APK that is never installed. The app downloads it from the same GitHub
// Release as itself, checks its SHA-256 and signing certificate, and loads it with a class loader (see
// app/src/main/kotlin/atom/app/ondevice/OnDevicePack.kt and docs/UPDATES.md).
plugins {
    alias(libs.plugins.android.application)
}

// The pack must carry exactly the app's version: the app loads only a pack with its own versionCode. Both are read
// from the app's build file, the only place they are written (the release workflow stamps them there).
val appBuildFile = rootProject.file("app/build.gradle.kts").readText()
val appVersionCode = Regex("""(?m)^\s*versionCode = (\d+)\s*$""").find(appBuildFile)?.groupValues?.get(1)?.toInt()
    ?: error("No versionCode in app/build.gradle.kts")
val appVersionName = Regex("""(?m)^\s*versionName = "([^"]*)"\s*$""").find(appBuildFile)?.groupValues?.get(1)
    ?: error("No versionName in app/build.gradle.kts")

android {
    ndkVersion = "30.0.16248370"
    namespace = "atom.ondevice.pack"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.namanwtf.atom.ondevice"
        minSdk = 30
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            // Not minified: the app's class loader resolves the pack's classes by their real names, and the pack
            // calls the app's own (kept) core, Kotlin and kotlinx classes.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The models are read straight from the APK file.
    androidResources { noCompress += "tflite" }

    packaging {
        // Stored uncompressed and page-aligned, so the system linker loads them straight from the APK
        // ("<pack.apk>!/lib/arm64-v8a"): nothing is extracted.
        jniLibs { useLegacyPackaging = false }
        dex { useLegacyPackaging = true }
    }

    lint {
        // A code container with no launcher activity and no icon, by design.
        disable += listOf("MissingApplicationIcon")
    }
}

// Core, Kotlin and kotlinx come from the app at run time (the class loader delegates them to the app), so the pack
// does not carry its own copies: two copies of one class would not be the same type.
val sharedWithApp = listOf(
    "org.jetbrains.kotlin" to "kotlin-stdlib",
    "org.jetbrains.kotlin" to "kotlin-stdlib-jdk7",
    "org.jetbrains.kotlin" to "kotlin-stdlib-jdk8",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-core-jvm",
    "org.jetbrains.kotlinx" to "kotlinx-coroutines-android",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-core",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-core-jvm",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-json",
    "org.jetbrains.kotlinx" to "kotlinx-serialization-json-jvm",
)
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    sharedWithApp.forEach { (group, module) -> exclude(group = group, module = module) }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":engine:litert"))
    implementation(project(":engine:llamacpp"))
    // LiteRT (TFLite runtime) for the wake-word models; CPU + XNNPack only.
    implementation("com.google.ai.edge.litert:litert:2.4.0")
}
