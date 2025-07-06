plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "dev.rubentxu.pipeline.backend"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-config"))

    implementation(libs.logback.classic)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlin.reflect)
    implementation(libs.kotlin.stdlib)
    implementation(libs.freemarker)
    implementation(libs.bundles.kotlin.scripting)
    implementation(libs.bundles.docker.zerodep)

    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.junit.jupiter)
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


tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}
