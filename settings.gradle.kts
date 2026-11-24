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

rootProject.name = "atom"

include(
    ":core",
    ":llm:cloud",
    ":engine:litert",
    ":engine:llamacpp",
    ":device",
    ":models",
    ":voice",
    ":app",
)
