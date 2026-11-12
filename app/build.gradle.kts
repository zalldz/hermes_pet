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
        versionCode = 25
        versionName = "2.4.1-baca-layar"
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


    // unit test parser (tidak ikut ke APK)
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}
