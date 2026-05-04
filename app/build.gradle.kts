plugins {
    id 'com.android.application'
    id 'org.jetbrains.kotlin.android'
}

android {
    namespace 'com.von.tracer'
    compileSdk 34

    defaultConfig {
        applicationId "com.von.tracer"
        minSdk 26
        targetSdk 34
        versionCode 1
        versionName "1.0"
    }

    buildTypes {
        release {
            minifyEnabled false
            proguardFiles getDefaultProguardFile('proguard-android-optimize.txt'), 'proguard-rules.pro'
        }
        debug {
            minifyEnabled false
        }
    }

    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = '17'
    }

    // Supaya frida-gadget-arm64.so tidak di-compress
    // Kalau di-compress, linker tidak bisa load langsung dari APK
    packagingOptions {
        jniLibs {
            useLegacyPackaging = true
        }
        // Jangan compress .so yang kita bundle di assets
        doNotStrip "*/arm64-v8a/libgadget.so"
    }

    // Izinkan bundle .so di assets
    aaptOptions {
        noCompress 'so'
    }

    buildFeatures {
        viewBinding false
    }
}

dependencies {
    // Core
    implementation 'androidx.core:core-ktx:1.12.0'
    implementation 'androidx.appcompat:appcompat:1.6.1'
    implementation 'com.google.android.material:material:1.11.0'

    // Layout
    implementation 'androidx.constraintlayout:constraintlayout:2.1.4'
    implementation 'androidx.recyclerview:recyclerview:1.3.2'

    // Fragment
    implementation 'androidx.fragment:fragment-ktx:1.6.2'

    // Coroutines
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3'

    // Activity result API
    implementation 'androidx.activity:activity-ktx:1.8.2'
}