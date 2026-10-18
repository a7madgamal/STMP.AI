// Top-level build file
plugins {
    id("com.android.application") version "8.7.3" apply false
    // Kotlin 2.4.x is required by litertlm-android 0.18.0, whose Kotlin metadata is
    // 2.4.0 — a 2.0.x compiler refuses it outright ("incompatible version of Kotlin").
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
}
