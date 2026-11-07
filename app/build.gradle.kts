import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// A release key from the environment (CI secrets, see README.md "Signing"). Without one, release
// builds are signed with the debug key so they still install for testing.
val releaseKeystore: String? = providers.environmentVariable("ANDROIDTTS_KEYSTORE").orNull

android {
    namespace = "io.github.enoky.androidtts"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.enoky.androidtts"
        minSdk = 24
        targetSdk = 36
        // Each CI run gets a higher version code, so a newer APK installs over an older one.
        versionCode = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull() ?: 1
        versionName = "0.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // libptts_ffi.so is built for these two only. JNA ships more ABIs, and without this filter
        // the app would install on 32-bit phones, where Phonon cannot load.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("ANDROIDTTS_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("ANDROIDTTS_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("ANDROIDTTS_KEY_PASSWORD").orNull
            }
        }
    }
    buildTypes {
        debug {
            // Installs beside the release build, as a second engine.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            resValue("string", "app_name", "AndroidTTS (debug)")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "AndroidTTS")
        }
    }
    buildFeatures { resValues = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("net.java.dev.jna:jna:5.17.0@aar")
    testImplementation("junit:junit:4.13.2")
    // The real org.json for JVM unit tests: android.jar only has stubs that throw.
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// An APK without libptts_ffi.so installs but crashes on first use, so refuse to package one.
val nativeLib = file("src/main/jniLibs/arm64-v8a/libptts_ffi.so")
val checkNativeLib = tasks.register("checkNativeLib") {
    description = "Fails when libptts_ffi.so has not been built into src/main/jniLibs."
    val lib = nativeLib
    doLast {
        if (!lib.isFile) {
            throw GradleException("$lib is missing. Build it with ./scripts/build-native.sh first (see README.md).")
        }
    }
}
tasks.configureEach {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") dependsOn(checkNativeLib)
}
