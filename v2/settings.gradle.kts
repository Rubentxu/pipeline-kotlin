pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "pipeline-v2"

include(
    ":pipeline-domain",
    ":pipeline-application",
    ":pipeline-scripting-api",
    ":pipeline-scripting-kotlin24",
    ":pipeline-testkit",
    ":pipeline-architecture-tests",
    ":pipeline-events",
)
