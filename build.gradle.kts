// Top-level build file
plugins {
    id("com.android.application") version "8.7.3" apply false
    // Kotlin 2.4.x is required by litertlm-android 0.18.0, whose Kotlin metadata is
    // 2.4.0 — a 2.0.x compiler refuses it outright ("incompatible version of Kotlin").
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // The shell lexer module is a plain JVM library, not an Android one — that is
    // deliberate and is what keeps `android.*` out of it (see :shellhl/build.gradle.kts).
    // Same version as the Android plugin above: two Kotlin versions in one build is a
    // classpath conflict waiting to happen.
    id("org.jetbrains.kotlin.jvm") version "2.4.21" apply false
}
