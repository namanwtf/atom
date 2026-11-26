import javax.inject.Inject

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
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
    // be re-stripped. They are built from source by buildProot below and never committed. The app
    // module's own packaging block decides the final APK; see LinuxEnv.
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf("**/libproot.so", "**/libproot_loader.so")
        }
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * Builds PRoot and its loader from source with the module's NDK (third_party/proot/build-proot.sh: pinned,
 * checksummed sources, path-independent and stripped). Needs bash, git, curl and make on the build machine.
 */
abstract class BuildProot : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val script: RegularFileProperty

    @get:Input
    abstract val ndkVersion: Property<String>

    @get:Internal
    abstract val ndkDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val execOps: ExecOperations

    @TaskAction
    fun build() {
        val out = outputDir.get().dir("arm64-v8a").asFile
        execOps.exec {
            commandLine(
                "bash", script.get().asFile.absolutePath,
                workDir.get().asFile.absolutePath, out.absolutePath, ndkDir.get().asFile.absolutePath,
            )
        }
    }
}

/** Hands the PRoot binaries to one variant's jniLibs (AGP gives every variant its own generated directory). */
abstract class CopyProot : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val binaries: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val fs: FileSystemOperations

    @TaskAction
    fun copy() {
        fs.copy {
            from(binaries)
            into(outputDir)
        }
    }
}

val prootNdkVersion = android.ndkVersion
val buildProot = tasks.register<BuildProot>("buildProot") {
    script.set(layout.projectDirectory.file("third_party/proot/build-proot.sh"))
    ndkVersion.set(prootNdkVersion)
    ndkDir.set(androidComponents.sdkComponents.sdkDirectory.map { it.dir("ndk/$prootNdkVersion") })
    workDir.set(layout.buildDirectory.dir("proot/work"))
    outputDir.set(layout.buildDirectory.dir("proot/jniLibs"))
}

androidComponents {
    onVariants { variant ->
        val copy = tasks.register<CopyProot>("copyProot${variant.name.replaceFirstChar { it.uppercase() }}") {
            binaries.set(buildProot.flatMap { it.outputDir })
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(copy, CopyProot::outputDir)
    }
}

dependencies {
    api(project(":core"))
    implementation(project(":ui"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime)
    // .tar.xz rootfs images (Debian). 0BSD.
    implementation("org.tukaani:xz:1.12")

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
