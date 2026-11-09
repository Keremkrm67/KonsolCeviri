plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
}

android {
    namespace = "com.playceviri.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.konsolceviri.tv"
        minSdk = 26
        // Yalnız TV uygulaması: Play'in TV için alt sınırı API 34 (31 Ağu 2026 itibarıyla)
        targetSdk = 34
        versionCode = 52
        versionName = "3.4"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // play: Google Play sürümü (yalnız kurallara uygun çeviriciler) · tam: GitHub sürümü (tüm ücretsiz çeviriciler)
    flavorDimensions += "dagitim"
    productFlavors {
        create("play") { dimension = "dagitim"; buildConfigField("boolean", "GAYRIRESMI", "false") }
        create("tam") { dimension = "dagitim"; buildConfigField("boolean", "GAYRIRESMI", "true"); versionNameSuffix = "-tam" }
    }

    signingConfigs {
        create("playceviri") {
            storeFile = file("../keystore/playceviri.jks")
            storePassword = System.getenv("PC_STORE_PASS") ?: "playceviri"
            keyAlias = "playceviri"
            keyPassword = System.getenv("PC_KEY_PASS") ?: "playceviri"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("playceviri")
        }
        debug {
            signingConfig = signingConfigs.getByName("playceviri")
        }
    }
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20231013")
}

android.testOptions.unitTests.isReturnDefaultValues = true
android.testOptions.unitTests.isIncludeAndroidResources = true
