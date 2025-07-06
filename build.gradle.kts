plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.scripting)
}

allprojects {
    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }
}
