plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "atom.voice"
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

    androidResources { noCompress += "tflite" }
}

dependencies {
    // Shared design system, feature + agent-host interfaces (brings Compose, Material3, core, serialization).
    implementation(project(":ui"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.activity.compose)
    // Cloud speech services (HTTP + WebSocket).
    implementation(libs.okhttp)
    // LiteRT (TFLite runtime) for the wake-word models; CPU + XNNPack only.
    implementation("com.google.ai.edge.litert:litert:2.4.0")

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
