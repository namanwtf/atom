plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "atom.vdisplay"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":ui"))
    implementation(project(":device"))
    // The run capsule's ViewfinderSlot lives in :overlay; this module fills it.
    implementation(project(":overlay"))
    // System enhancements screen: LSPosed connection status and hook settings.
    implementation(project(":xposed"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
