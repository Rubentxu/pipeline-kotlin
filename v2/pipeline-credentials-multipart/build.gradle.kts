plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-credentials-api"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
