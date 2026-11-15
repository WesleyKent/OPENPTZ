plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val supportedAndroidAbi = "arm64-v8a"
val swiftCoreJniLibsRoot = layout.buildDirectory.dir("generated/swiftCore/jniLibs")
val swiftCoreArm64Directory = swiftCoreJniLibsRoot.map { it.dir(supportedAndroidAbi) }
val repositoryRoot = rootProject.projectDir.parentFile.parentFile
val stageSwiftCoreScript = repositoryRoot.resolve("scripts/android-stage-swift-core.sh")
val ndiSdkDirectory = layout.projectDirectory.dir("../../../.local/NDI SDK for Android")
val ndiLegalAssetsRoot = layout.buildDirectory.dir("generated/ndi/assets")

val resolvedVersionCode: Int =
    (findProperty("versionCode") ?: property("openpocketcine.versionCode")).toString().toInt()
val resolvedVersionName: String = property("openpocketcine.versionName").toString()

android {
    namespace = "com.opencapture.openpocketcine"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.opencapture.openptz"
        minSdk = 29
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += supportedAndroidAbi
        }
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DOPENPTZ_NDI_SDK_DIR=${ndiSdkDirectory.asFile.absolutePath}"
            }
        }
    }

    ndkVersion = "28.2.13676358"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    sourceSets {
        getByName("main").jniLibs.directories.apply {
            clear()
            add(swiftCoreJniLibsRoot.get().asFile.absolutePath)
            if (ndiSdkDirectory.file("lib/$supportedAndroidAbi/libndi.so").asFile.isFile) {
                add(ndiSdkDirectory.dir("lib").asFile.absolutePath)
            }
        }
        getByName("main").assets.srcDir(ndiLegalAssetsRoot.get().asFile.absolutePath)
    }
}

val stageNdiLegal =
    tasks.register<Sync>("stageNdiLegal") {
        group = "build"
        description = "Stage the NDI runtime license and third-party notices into the APK."
        into(ndiLegalAssetsRoot.map { it.dir("legal/ndi") })
        from(ndiSdkDirectory.file("NDI SDK License Agreement.txt"))
        from(ndiSdkDirectory.dir("licenses"))
    }

tasks.named("preBuild").configure { dependsOn(stageNdiLegal) }

val stageSwiftCore =
    tasks.register<Exec>("stageSwiftCore") {
        group = "build"
        description = "Cross-compile and stage the Swift camera core for arm64-v8a."
        workingDir = repositoryRoot
        inputs.files(
            fileTree(repositoryRoot) {
                include("Package.swift", "Package.resolved", "Sources/**")
            },
            stageSwiftCoreScript,
        )
        inputs.property("swiftExecutable", providers.environmentVariable("SWIFT_EXECUTABLE").orElse("auto"))
        inputs.property(
            "swiftAndroidSdk",
            providers.environmentVariable("SWIFT_ANDROID_SDK_ID").orElse("swift-6.3.3-RELEASE_android"),
        )
        outputs.dir(swiftCoreArm64Directory)
        commandLine(
            "bash",
            stageSwiftCoreScript.absolutePath,
            "--output",
            swiftCoreArm64Directory.get().asFile.absolutePath,
        )
    }

// OPENPTZ's Tail 2 path is pure Kotlin and must build without the legacy DJI
// Swift/DUML runtime. `stageSwiftCore` remains available for archival Pocket
// builds, but is no longer an APK prerequisite.

// Compose BOM / androidx.core AARs currently declare compileSdk 37. Local and
// CI SDKs stay on 36 (same gate OpenZCine uses). Disable only the AAR metadata
// check so the rest of the AGP graph still runs.
afterEvaluate {
    tasks.matching { it.name.startsWith("check") && it.name.endsWith("AarMetadata") }
        .configureEach { enabled = false }
}

dependencies {
    implementation(project(":core-api"))

    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.kyant.backdrop)
    implementation(libs.kyant.shapes)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.media3.common)
    implementation(libs.media3.effect)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.ui)
    implementation(libs.okhttp)
    implementation(libs.uvc.camera)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.json)
    testImplementation(libs.kotlinx.coroutines.test)
}
