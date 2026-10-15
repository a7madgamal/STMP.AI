plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mido.phonebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mido.phonebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 9
        versionName = "0.1.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        allWarningsAsErrors = true
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
    }

    /*
     * Android Lint (the ESLint equivalent for Android). Runs on build so issues
     * cannot silently accumulate.
     */
    lint {
        // Real correctness issues fail the build. Stylistic/i18n noise for a
        // single-user dev app is reported as warnings instead.
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        disable += setOf(
            "GradleDependency",
            "OldTargetApi",
            "NewerVersionAvailable",
        )
        // These stay as warnings rather than errors.
        warning += setOf("SetTextI18n", "HardcodedText", "UnusedResources")
        error += setOf("NewApi", "InlinedApi", "MissingApplicationIcon", "DataExtractionRules")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
