plugins {
    alias(libs.plugins.kotlin.jvm)
    id("dev.rubentxu.pipeline.steps")
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
    
    // TODO: Re-enable testing framework when compatibility is fixed
    // testImplementation(project(":pipeline-testing-framework:runtime"))
}


java {
    sourceCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
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
