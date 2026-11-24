plugins {
    alias(libs.plugins.android.library)
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

    androidResources { noCompress += "tflite" }
}

dependencies {
    implementation(libs.kotlinx.coroutines.android)
    // LiteRT (TFLite runtime) for the wake-word models; CPU + XNNPack only.
    implementation("com.google.ai.edge.litert:litert:2.4.0")

    testImplementation(libs.kotlin.test.junit)
}
