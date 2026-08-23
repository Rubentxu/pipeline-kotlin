plugins {
    kotlin("jvm")
}

group = "com.pipeline.v2.fitness"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    // Honour system property override (Gradle forwards -Pfitness.v2.root=...).
    // Default: this module lives at v2/pipeline-architecture-tests/, so ../ is v2/.
    val v2RootDefault = projectDir.parentFile.absolutePath
    systemProperty("fitness.v2.root", System.getProperty("fitness.v2.root", v2RootDefault))
}

// Cross-project runtime-classpath capture wiring (configure-time hook, zero M0-R2 build-file edits)
val v2Modules = listOf(
    "pipeline-domain",
    "pipeline-application",
    "pipeline-scripting-api",
    "pipeline-scripting-kotlin24",
    "pipeline-testkit",
    "pipeline-events",
)

gradle.allprojects {
    if (name in v2Modules) {
        val projName = name
        val capture = tasks.register("runtimeClasspathCapture", DefaultTask::class) {
            val out = layout.buildDirectory.file("fitness/${projName}-runtime-classpath.txt")
            outputs.file(out)
            doLast {
                val cp = configurations.named("runtimeClasspath").get()
                    .files
                    .map { it.name }
                    .sorted()
                out.get().asFile.writeText(cp.joinToString("\n"))
            }
        }
        tasks.matching { it.name == "test" }.configureEach {
            dependsOn(capture)
        }
    }
}

// Ensure :pipeline-architecture-tests:test runs after all four capture tasks
val captureTaskPaths = v2Modules.map { ":$it:runtimeClasspathCapture" }
tasks.named("test") {
    dependsOn(captureTaskPaths)
}
