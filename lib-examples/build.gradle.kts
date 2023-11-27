plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"

}

group = "dev.rubentxu.pipeline.examples"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-config"))

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect:1.9.21")


    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:5.7.2")
}

tasks.test {
    useJUnitPlatform()
}