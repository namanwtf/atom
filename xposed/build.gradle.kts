plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "atom.xposed"
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

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    // Modern Xposed API (Apache-2.0, Maven Central). Provided by the framework at runtime, so compileOnly.
    compileOnly("io.github.libxposed:api:102.0.0")
    // App side: the framework hands Atom a service binder (status, scope, remote preferences). Apache-2.0.
    implementation("io.github.libxposed:service:102.0.0")
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.kotlin.test.junit)
}
