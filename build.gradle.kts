plugins {
    kotlin("jvm") version "1.8.22"
    id("org.jetbrains.kotlin.plugin.scripting") version "1.8.22"
}

val kotlinVersion: String by extra("1.8.22")
val kotlinCoroutinesVersion: String by extra("1.7.3")


allprojects {

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }

}
