plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Copies the repository's legal files into the APK, so the in-app notices can never drift from the repo. */
abstract class BundleLegalFiles : DefaultTask() {
    @get:InputFile abstract val notice: RegularFileProperty
    @get:InputFile abstract val license: RegularFileProperty
    /** Every `licenses/<Name>.txt` is bundled as `LICENSE-<Name>`. */
    @get:InputDirectory abstract val licenses: DirectoryProperty
    @get:InputFile abstract val mitLlamaCpp: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        // Flat, non-recursive clean: this folder only ever holds the plain files written below.
        val dir = outputDir.get().asFile.resolve("legal").apply { mkdirs() }
        dir.listFiles().orEmpty().forEach { it.delete() }
        notice.get().asFile.copyTo(dir.resolve("NOTICE"))
        license.get().asFile.copyTo(dir.resolve("LICENSE-GPL-3.0"))
        licenses.get().asFile.listFiles { f -> f.extension == "txt" }.orEmpty().forEach {
            it.copyTo(dir.resolve("LICENSE-" + it.nameWithoutExtension))
        }
        mitLlamaCpp.get().asFile.copyTo(dir.resolve("LICENSE-MIT-llama.cpp"))
    }
}

val bundleLegal = tasks.register<BundleLegalFiles>("bundleLegalFiles") {
    val root = rootProject.layout.projectDirectory
    notice.set(root.file("NOTICE"))
    license.set(root.file("LICENSE"))
    licenses.set(root.dir("licenses"))
    mitLlamaCpp.set(root.file("engine/llamacpp/third_party/llama.cpp/LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleLegal, BundleLegalFiles::outputDir)
    }
}

android {
    // Same NDK as the native modules: without it the release build cannot strip debug symbols from the
    // bundled native libraries and ships them unstripped.
    ndkVersion = "30.0.16248370"
    namespace = "atom.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        // The same id as the earlier Atom app, so a phone that has it installed updates in place (Android also
        // requires the same signing certificate; see docs/UPDATES.md).
        applicationId = "io.github.namanwtf.atom"
        minSdk = 30
        targetSdk = 36
        // versionCode rule: yyyyMMdd * 100 + the 15-minute slot of the UTC day (0..95), stamped by
        // .github/workflows/release.yml. It never decreases: Android refuses an update with a lower versionCode than
        // the installed one. The earlier app's last release was below 2026112500. This committed value is only the
        // floor; the release workflow always publishes something higher (and never commits its value back).
        versionCode = 2026112600
        versionName = "4.0.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        debug {
            // A debug build installs next to the release app instead of replacing it.
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    packaging {
        // proot must exist as a real file in nativeLibraryDir to be executable (Android forbids exec from app data).
        jniLibs { useLegacyPackaging = true }
        // Compressed dex: a much smaller download for a negligible first-start cost.
        dex { useLegacyPackaging = true }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm:cloud"))
    // No on-device engines here: LiteRT-LM, llama.cpp and the wake-word runtime ship in the optional on-device AI
    // pack (:ondevice-pack), which the app downloads and loads on request (atom.app.ondevice.OnDevicePack).
    implementation(project(":device"))
    implementation(project(":context"))
    implementation(project(":models"))
    implementation(project(":voice"))
    implementation(project(":ui"))
    implementation(project(":general"))
    implementation(project(":characters"))
    implementation(project(":overlay"))
    implementation(project(":linux"))
    implementation(project(":web"))
    implementation(project(":extend"))
    implementation(project(":automation"))
    implementation(project(":vdisplay"))
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
