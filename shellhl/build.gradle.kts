/*
 * shellhl — a bash/POSIX shell lexer with zsh-syntax-highlighting's vocabulary.
 *
 * ============================ THE ONE HARD RULE ============================
 * This module MUST NOT import anything from `android.*`.
 *
 * The whole reason it is a separate Gradle module instead of a package inside
 * `:app` is that the Android plugin is simply not applied here, so a stray
 * `import android.graphics.Color` fails at COMPILE time instead of being caught
 * by review. That is the enforcement mechanism — a convention would not be one.
 *
 * The reason it matters: the only alternative to this lexer was a tree-sitter
 * bash grammar, and no Android-loadable build of one exists (see
 * docs/plans/SHELL-HIGHLIGHT-PLAN.md §1). Keep this module free of Android and
 * it can be published and reused on any JVM; couple it and it can only ever be
 * a phone library.
 * ==========================================================================
 *
 * Zero dependencies. No coroutines, no JSON, no logging. A shell lexer needs a
 * scanner, a table of reserved words, and nothing else.
 */
plugins {
    id("org.jetbrains.kotlin.jvm")
}

kotlin {
    compilerOptions {
        // Same strictness as the app: a warning here is a bug somebody will read
        // as "it works". The app module already sets this; a library that is less
        // strict than its consumer is a library that ships the warnings.
        allWarningsAsErrors = true
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // No `-Xjvm-default`: it is deprecated as of Kotlin 2.2 and, with
        // allWarningsAsErrors, the deprecation warning fails the build. Nothing in
        // this module exposes an interface with a default method to Java, so the flag
        // was cargo — removed rather than migrated to `-jvm-default`.
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    // Deliberately empty. If a line appears here, the module's zero-dependency
    // claim in the plan is no longer true and the plan needs amending, not this
    // file.
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The corpus is 271 files of plain text; the whole suite runs in under a
    // second. One fork, no parallelism — this box shares memory with the gateway
    // (see AGENTS.md "MACHINE SAFETY").
    maxParallelForks = 1
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
