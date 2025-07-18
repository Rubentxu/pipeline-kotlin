plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotest)
    alias(libs.plugins.ksp)
}

group = "dev.rubentxu.pipeline.core"
version = "1.0-SNAPSHOT"

dependencies {
    // Plugin annotations for @Step and related annotations
    implementation(project(":pipeline-steps-system:plugin-annotations"))
    
    // Compiler plugin for @Step transformation
    kotlinCompilerPluginClasspath(project(":pipeline-steps-system:compiler-plugin"))

    implementation(libs.snakeyaml)
    implementation(libs.kotlin.serialization)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.gradle.tooling.api)
    implementation(libs.jgit)
    implementation(libs.logback.classic)
    implementation(libs.bundles.kotlin.scripting)
    implementation(libs.bundles.docker)
//    compileOnly(libs.bundles.graalvm) // Only needed for compilation, not runtime
    implementation(libs.bundles.maven.resolver)

    // Kotest required for generated test frameworks
    implementation(libs.bundles.kotest)

    testImplementation(kotlin("test"))
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.mockk)
    testImplementation(libs.junit.jupiter)
    // GraalVM dependencies for tests that use sandbox functionality
//    testImplementation(libs.bundles.graalvm)
}



java {
    sourceCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
        exclude("**/disabled/**")
        dependsOn(":pipeline-steps-system:compiler-plugin:jar")
    }
    compileTestKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    test {
        useJUnitPlatform()
//        jvmArgs()
    }
}

tasks.withType<Test> {
    jvmArgs("--add-opens", "java.base/java.util=ALL-UNNAMED")
}

tasks
    .withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()
    .configureEach {
        compilerOptions
            .languageVersion
            .set(
                org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2
            )
    }