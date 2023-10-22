plugins {
    kotlin("jvm") version "1.9.10"
    application
    id("io.kotest") version "0.4.10"

}

group = "dev.rubentxu.pipeline"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation(kotlin("test"))

    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    implementation("io.kotest:kotest-property-jvm:5.7.2")

}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("MainKt")
}