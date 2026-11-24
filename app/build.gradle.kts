plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

/** Copies the repository's legal files into the APK, so the in-app notices can never drift from the repo. */
abstract class BundleLegalFiles : DefaultTask() {
    @get:InputFile abstract val notice: RegularFileProperty
    @get:InputFile abstract val license: RegularFileProperty
    @get:InputFile abstract val apache: RegularFileProperty
    @get:InputFile abstract val mitLlamaCpp: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val dir = outputDir.get().asFile.resolve("legal").apply {
            deleteRecursively()
            mkdirs()
        }
        notice.get().asFile.copyTo(dir.resolve("NOTICE"))
        license.get().asFile.copyTo(dir.resolve("LICENSE-GPL-3.0"))
        apache.get().asFile.copyTo(dir.resolve("LICENSE-Apache-2.0"))
        mitLlamaCpp.get().asFile.copyTo(dir.resolve("LICENSE-MIT-llama.cpp"))
    }
}

val bundleLegal = tasks.register<BundleLegalFiles>("bundleLegalFiles") {
    val root = rootProject.layout.projectDirectory
    notice.set(root.file("NOTICE"))
    license.set(root.file("LICENSE"))
    apache.set(root.file("licenses/Apache-2.0.txt"))
    mitLlamaCpp.set(root.file("engine/llamacpp/third_party/llama.cpp/LICENSE"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(bundleLegal, BundleLegalFiles::outputDir)
    }
}

android {
    namespace = "atom.app"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "io.github.namanwtf.atomnext"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
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
        jniLibs { useLegacyPackaging = false }
    }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm:cloud"))
    implementation(project(":engine:litert"))
    implementation(project(":engine:llamacpp"))
    implementation(project(":device"))
    implementation(project(":models"))
    implementation(project(":voice"))

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
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
