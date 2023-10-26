plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"

}

group = "dev.rubentxu.pipeline.core"
version = "1.0-SNAPSHOT"

val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.gradle:gradle-tooling-api:8.4")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")


    testImplementation(kotlin("test"))

    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:5.7.2")

}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("core")

    }

}