plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"
version = "0.36.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        // KotlinScriptedSourceMapper deliberately reaches into compiler PSI/CLI
        // internals; Kotlin 2.4 gates both behind opt-in markers.
        freeCompilerArgs.addAll(
            "-opt-in=org.jetbrains.kotlin.K1Deprecation",
            "-opt-in=org.jetbrains.kotlin.config.CompilerConfiguration.Internals",
        )
    }
}

dependencies {
    implementation(libs.kotlin.scripting.jvm.host)
    implementation(libs.kotlin.scripting.jvm)
    implementation(libs.kotlin.compiler.embeddable)
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-domain"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
