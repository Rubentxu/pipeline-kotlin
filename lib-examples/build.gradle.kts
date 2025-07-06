plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotest)
}

group = "dev.rubentxu.pipeline.examples"
version = "unspecified"

dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-config"))
    implementation(libs.gson)
    implementation(libs.kotlin.reflect)

    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.junit.jupiter)
}

tasks.test {
    useJUnitPlatform()
}