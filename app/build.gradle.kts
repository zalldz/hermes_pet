plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hermespet"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermespet"
        minSdk = 26
        targetSdk = 34
        versionCode = 22
        versionName = "2.2-slime"
    }

    // kunci debug tetap (di-commit) supaya semua build CI bertanda tangan sama
    // dan update APK tidak pernah minta uninstall
    signingConfigs {
        create("shared") {
            storeFile = rootProject.file("signing/debug-keystore.p12")
            storePassword = "hermespet"
            keyAlias = "hermespet"
            keyPassword = "hermespet"
            storeType = "PKCS12"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("shared")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("shared")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val camerax = "1.3.4"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    // Deteksi wajah on-device (senyum, mata terbuka) — tanpa kirim data keluar HP
    implementation("com.google.mlkit:face-detection:16.1.7")

    // Walkie-talkie LAN: pakai NSD (android.net.nsd) + socket bawaan — tanpa dependensi tambahan

    // unit test parser (tidak ikut ke APK)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
