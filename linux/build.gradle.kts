plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "atom.linux"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    ndkVersion = "30.0.16248370"

    defaultConfig {
        minSdk = 30
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf("-DCMAKE_BUILD_TYPE=Release")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "4.1.2"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // libproot.so / libproot_loader.so are executables, not libraries: Android only lets an app exec
    // files from nativeLibraryDir, so they must be extracted on install (legacy packaging) and must not
    // be re-stripped. The app module's own packaging block decides the final APK; see LinuxEnv.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot_loader.so")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime)
    // .tar.xz rootfs images (Debian). 0BSD.
    implementation("org.tukaani:xz:1.12")

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
