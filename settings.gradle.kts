pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "STMP"
include(":app")
// The shell lexer. A separate module, not a package inside :app, so that
// splitting it into its own repo later is a one-line change here — and so the
// "no android.* import" rule is enforced by the build graph rather than by
// review. See docs/plans/SHELL-HIGHLIGHT-PLAN.md §4.
include(":shellhl")
