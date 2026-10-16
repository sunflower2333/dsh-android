plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.sunflower2333.dsh"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.sunflower2333.dsh"
        // DSH's Android target is Android 11 and newer. The bundled Node
        // runtime is verified on Bionic API 30+.
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += "arm64-v8a" }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging {
        jniLibs.useLegacyPackaging = true
    }
    androidResources { noCompress += "zip" }
}

// Runtime assets are produced by the Android runtime packaging step.  Never
// create an empty placeholder: an APK without DSH is unusable.
tasks.register("verifyRuntimeAssets") {
    val manifest = file("src/main/assets/runtime/manifest.json")
    val runtimeZip = file("src/main/assets/runtime/runtime.zip")
    val node = file("src/main/jniLibs/arm64-v8a/libdsh_node.so")
    val cxx = file("src/main/jniLibs/arm64-v8a/libc++_shared.so")
    val esbuild = file("src/main/jniLibs/arm64-v8a/libdsh_esbuild.so")
    val bash = file("src/main/jniLibs/arm64-v8a/libdsh_bash.so")
    val nativeNotices = listOf("node-LICENSE.txt", "libcxx-NOTICE.txt")
        .map { file("src/main/assets/licenses/native/$it") }
    doLast {
        check(manifest.isFile) {
            "Missing ${manifest.relativeTo(projectDir)}; generate the real DSH runtime first"
        }
        check(runtimeZip.isFile) {
            "Missing ${runtimeZip.relativeTo(projectDir)}; generate the real DSH runtime first"
        }
        check(node.isFile && node.canRead()) {
            "Missing ${node.relativeTo(projectDir)}; build the arm64 Node runtime first"
        }
        check(cxx.isFile && cxx.canRead()) {
            "Missing ${cxx.relativeTo(projectDir)}; copy the NDK libc++_shared.so first"
        }
        check(esbuild.isFile && esbuild.canRead()) {
            "Missing ${esbuild.relativeTo(projectDir)}; stage the Android esbuild runtime first"
        }
        check(bash.isFile && bash.canRead()) {
            "Missing ${bash.relativeTo(projectDir)}; build the Android Bash runtime first"
        }
        nativeNotices.forEach { notice ->
            check(notice.isFile && notice.length() > 0) {
                "Missing ${notice.relativeTo(projectDir)}; stage the native runtime license notices first"
            }
        }
    }
}

tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) dependsOn("verifyRuntimeAssets")
}

dependencies {
    // libsu delegates root-shell creation and authorization to the device's
    // installed root manager (including Magisk and KernelSU).
    implementation("com.github.topjohnwu.libsu:core:6.0.0")
    testImplementation("junit:junit:4.13.2")
    // The Android SDK's org.json classes are API stubs on the host JVM. Use
    // the upstream implementation for capability-manager unit tests that
    // exercise the JSON status/error contract.
    testImplementation("org.json:json:20240303")
}
