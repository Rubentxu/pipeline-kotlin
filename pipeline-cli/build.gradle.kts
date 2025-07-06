plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    application
}

group = "dev.rubentxu.pipeline.cli"
version = "0.1.0"

dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-config"))
    implementation(project(":pipeline-backend"))

    // Modern CLI with Kotlin Native libraries
    implementation(libs.clikt)
    implementation(libs.mordant)
    implementation(libs.okio)
    implementation(libs.kaml)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    
    // Logging
    implementation(libs.logback.core)
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)

    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.junit.jupiter)
}

application {
    mainClass.set("dev.rubentxu.pipeline.cli.MainKt")
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
    
    shadowJar {
        isZip64 = true
        archiveBaseName.set("pipeline-cli")
        archiveClassifier.set("")
        archiveVersion.set("")
    }
}





tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}
