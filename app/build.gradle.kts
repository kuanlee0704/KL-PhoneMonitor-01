plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 每次 GitHub Actions 執行編號遞增 => versionCode 一定越來越大 => 可直接覆蓋安裝
val runNumber = (System.getenv("RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.example.phonemonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.phonemonitor"   // 發布後請勿再改,否則無法覆蓋更新
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.jks")
            storePassword = "phonemonitor"
            keyAlias = "monitor"
            keyPassword = "phonemonitor"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
