plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"

}

group = "dev.rubentxu.pipeline.core"
version = "1.0-SNAPSHOT"



val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra

dependencies {

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.gradle:gradle-tooling-api:8.4")
    implementation("org.eclipse.jgit:org.eclipse.jgit:6.7.0.202309050840-r")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Casc
    implementation("org.apache.commons:commons-lang3:3.13.0")
    implementation("org.apache.commons:commons-text:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")



    testImplementation(kotlin("test"))

    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:5.7.2")

}



java {
    sourceCompatibility = JavaVersion.toVersion("17")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    test {
        useJUnitPlatform()
    }
}
