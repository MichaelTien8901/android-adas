plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.adasedge.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.adasedge.app"
        // S22+ ships Android 12 (API 31). Typed foreground services (API 34) are
        // handled conditionally at runtime.
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0-poc"

        ndk {
            // ADAS NPU pipeline targets arm64 only (Snapdragon Hexagon).
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    // Native model assets (.onnx / .bin context binaries) must not be compressed
    // so they can be mmap'd by the runtime.
    androidResources {
        noCompress += listOf("onnx", "bin", "tflite", "dlc")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // CameraX
    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Fused location (GPS speed)
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // OpenCV (classical-CV lane fallback + image ops). Published to Maven Central.
    implementation("org.opencv:opencv:4.10.0")

    // Inference fallbacks. Primary QNN path is loaded via JNI against the
    // Qualcomm AI Engine Direct .so libraries shipped in jniLibs (see tools/README).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
    implementation("org.tensorflow:tensorflow-lite:2.16.1")
    implementation("org.tensorflow:tensorflow-lite-gpu:2.16.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
