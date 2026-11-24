plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "atom.engine.litert"
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
}

dependencies {
    api(project(":core"))
    implementation(libs.litertlm)
    implementation(libs.kotlinx.coroutines.android)
}
