plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.scripting)
    alias(libs.plugins.dokka)
}

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
}

// Dokka v2 configuration will be applied by default
// Documentation will be generated to build/dokka/html
