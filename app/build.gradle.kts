plugins {
    id("com.android.application")
}

android {
    namespace = "com.srfn.simwheel"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.srfn.simwheel"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    // Don't let lint fail CI for this small app.
    lint {
        abortOnError = false
    }
}

// The app intentionally uses only the Android framework (no AndroidX), so it
// also builds with the SDK-free offline toolchain in tools/build_apk.sh.
dependencies {
}
