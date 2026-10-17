plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mido.phonebridge"
    compileSdk = 35

    /*
     * Stable signing. Android ties runtime permissions (notifications, full-screen
     * intent, notification-listener binding) to the app's signing identity. If the
     * key changes between builds, the OS treats the install as a DIFFERENT app and
     * resets those grants. Pinning one project keystore keeps the identity stable,
     * so an update preserves permissions and data.
     */
    signingConfigs {
        create("stable") {
            storeFile = file("../keystore/phonebridge.jks")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "com.mido.phonebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 65
        versionName = "0.3.3"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("stable")
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
        buildConfig = true
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
    // BiometricPrompt for the approval gate: STRONG biometrics or device credential.
    implementation("androidx.biometric:biometric:1.1.0")
    // Google Identity Authorization API (Credential Manager successor for
    // authorization-code + offline access). Replaces the retired GoogleSignIn.
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}