plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))

    // Spring AntPathMatcher for Ant-style glob matching
    implementation("org.springframework:spring-core:6.2.4")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
