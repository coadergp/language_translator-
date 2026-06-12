import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing config is read from keystore.properties (NOT committed to VCS).
// Copy keystore.properties.template → keystore.properties and fill in real values, or
// configure App Signing by Google Play and upload an unsigned/upload-key-signed bundle.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
}

// The eSpeak-ng phonemizer is OPTIONAL native code. We only enable the NDK/CMake build
// when both the prebuilt library and its headers are present, so the project still builds
// out-of-the-box (PiperTTS falls back to a placeholder phonemizer). Drop in:
//   app/src/main/jniLibs/<abi>/libespeak-ng.so   (per ABI)
//   app/src/main/cpp/include/espeak-ng/speak_lib.h
// and the NDK build activates automatically. See README "Phonemizer (eSpeak-ng)".
val espeakAvailable =
    file("src/main/cpp/include/espeak-ng/speak_lib.h").exists() &&
        (file("src/main/jniLibs/arm64-v8a/libespeak-ng.so").exists() ||
            file("src/main/jniLibs/armeabi-v7a/libespeak-ng.so").exists())

android {
    namespace = "com.eartranslator"
    // Play requires a recent target SDK. 35 = Android 15 (the bar for new apps/updates
    // through 2026). compileSdk matches.
    compileSdk = 35

    defaultConfig {
        applicationId = "com.eartranslator"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        if (espeakAvailable) {
            // We ship a prebuilt libespeak-ng.so only for arm64-v8a (covers ~all modern
            // phones); restrict ABIs so the NDK build doesn't fail looking for others.
            ndk { abiFilters += "arm64-v8a" }
            externalNativeBuild { cmake { cppFlags += "-std=c++17" } }
        }
    }

    if (espeakAvailable) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // R8: shrink + obfuscate + resource shrinking for a smaller, store-ready build.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true   // needed for BuildConfig.DEBUG gating (e.g. privacy-safe logging)
    }

    testOptions {
        unitTests {
            // Let android.jar stubs (e.g. android.util.Log) return defaults instead of
            // throwing, so pure-logic classes that log can run under plain JVM unit tests.
            isReturnDefaultValues = true
        }
    }

    // ONNX model files are large and already compressed internally; do not let
    // aapt re-compress them, otherwise mmap-based loading via assets fails.
    androidResources {
        noCompress += listOf("onnx", "spm", "json", "bin")
    }

    packaging {
        // ONNX Runtime ships native .so per ABI; keep them all.
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ONNX Runtime — on-device inference for VAD / ASR / MT / TTS
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.16.3")

    // JSON parsing for vocab.json and piper .onnx.json configs
    implementation("org.json:json:20240303")

    // Unit tests (pure-JVM logic: tokenizers, feature extraction, lexicon)
    testImplementation("junit:junit:4.13.2")
}
