plugins {
    kotlin("jvm") version "1.9.21"
    id("org.jetbrains.kotlin.plugin.scripting") version "1.9.21"
}

val kotlinVersion: String by extra("1.9.21")
val kotlinCoroutinesVersion: String by extra("1.7.3")
val kotestVersion: String by extra("5.8.0")


allprojects {

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }

}
