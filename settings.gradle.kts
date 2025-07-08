pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven {
            url = uri("https://plugins.gradle.org/m2/")
        }
    }
}

rootProject.name = "pipeline-kotlin"

include(":core")
include(":pipeline-cli")
include(":pipeline-config")
include(":pipeline-backend")
include(":lib-examples")

// Framework de testing organizado como módulo padre con submódulos
include(":pipeline-testing-framework")
include(":pipeline-testing-framework:annotations")
include(":pipeline-testing-framework:compiler-plugin")
include(":pipeline-testing-framework:runtime")
