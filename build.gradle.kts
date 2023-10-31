plugins {
    kotlin("jvm") version "1.9.10"
}

val kotlinVersion: String by extra("1.9.10")
val kotlinCoroutinesVersion: String by extra("1.7.3")


allprojects {

    repositories {
        mavenCentral()
        maven { url = uri("https://repo.gradle.org/gradle/libs-releases") }
    }

}
