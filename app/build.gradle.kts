plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.atothei.roadhouse.overlay"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.atothei.roadhouse.overlay"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // No applicationIdSuffix on purpose: the debug APK installs as
            // com.atothei.roadhouse.overlay, the same package as release.
            versionNameSuffix = "-debug"
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
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    // EncryptedSharedPreferences for the BYOK Gemini key. No key is ever
    // compiled into the app; this only protects the key the user pastes in.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
