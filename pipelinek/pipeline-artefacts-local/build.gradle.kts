plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

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
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(libs.kotlinx.coroutines.core)

    // Spring AntPathMatcher for Ant-style glob matching (single-class usage;
    // dependency removal tracked as backlog). 6.2.19 clears the two spring-core
    // advisories osv-scanner flagged on 6.2.4, including the AntPathMatcher ReDoS
    // (GHSA-659m-px2c-25wj) — patterns here are user-supplied.
    implementation(libs.spring.core)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
