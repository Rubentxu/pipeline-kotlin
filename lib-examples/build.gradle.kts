val ktorVersion: String by project
val kotlinVersion: String by project
val logbackVersion: String by project
val kotlinCoroutinesVersion:  String by project
val kotestVersion:  String by project
val appVersion:  String by project


plugins {
    kotlin("jvm") version "1.9.21"
    id("io.kotest") version "0.4.10"

}

group = "dev.rubentxu.pipeline.examples"
version = appVersion

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":pipeline-dsl"))
    implementation(project(":pipeline-model"))

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