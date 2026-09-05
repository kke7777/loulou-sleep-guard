plugins {
    id("com.android.application")
}

android {
    namespace = "com.rabbit.sleepguard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rabbit.sleepguard"
        minSdk = 26
        targetSdk = 35
        versionCode = 110
        versionName = "1.1.0-loulou"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".test"
            versionNameSuffix = "-test"
            resValue("string", "app_name", "再不去睡露露就要生气啦（测试版）")
        }
        release {
            val keystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
            if (keystorePath != null) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(keystorePath)
                    storePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").get()
                    keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").get()
                    keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").get()
                }
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions { unitTests.isIncludeAndroidResources = true }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
}
