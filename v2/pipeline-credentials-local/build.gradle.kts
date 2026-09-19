plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-credentials-api"))
    implementation(libs.bouncycastle.bcprov)
    implementation(libs.bouncycastle.bcpkix)
    implementation(libs.bouncycastle.bcutil)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
