plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.scrollstop.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.scrollstop.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "3.1"
    }

    // Every build (your PC, GitHub Actions, Android Studio) is signed with the same key,
    // so a new APK installs over the old one and keeps your settings and stats.
    // To use your own private key, set SCROLLSTOP_KEYSTORE / _PASSWORD / _ALIAS env vars.
    signingConfigs {
        create("shared") {
            storeFile = file(System.getenv("SCROLLSTOP_KEYSTORE") ?: "scrollstop.jks")
            storePassword = System.getenv("SCROLLSTOP_PASSWORD") ?: "scrollstop"
            keyAlias = System.getenv("SCROLLSTOP_ALIAS") ?: "scrollstop"
            keyPassword = System.getenv("SCROLLSTOP_PASSWORD") ?: "scrollstop"
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
    buildFeatures {
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // SCROLLSTOP_SCREENSHOTS=/some/dir ./gradlew testDebugUnitTest  saves PNGs of every screen
                System.getenv("SCROLLSTOP_SCREENSHOTS")?.let { test.systemProperty("scrollstop.screenshots", it) }
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.webkit:webkit:1.12.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
}
