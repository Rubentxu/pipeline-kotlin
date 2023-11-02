pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "pipeline-kotlin"

include("core")
//include("pipeline-script")
include("pipeline-cli")
