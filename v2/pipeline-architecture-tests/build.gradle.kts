plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2.fitness"
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
    testImplementation("org.jetbrains.kotlin:kotlin-reflect")
    testImplementation(project(":pipeline-events"))
    testImplementation(project(":pipeline-scripting-api"))
    testImplementation(project(":pipeline-artefacts-local"))
    testImplementation(project(":pipeline-step-sdk:api"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly(project(":pipeline-binding-factory"))
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
    "pipeline-protocol",
    "pipeline-scripting-api",
    "pipeline-scripting-kotlin24",
    "pipeline-testkit",
    "pipeline-events",
    "pipeline-step-sdk:api",
    "pipeline-step-sdk:processor",
    "pipeline-step-sdk:runtime",
    "pipeline-binding-factory",
)

// Map module names to project paths
val v2ModulePaths = mapOf(
    "pipeline-domain" to ":pipeline-domain",
    "pipeline-application" to ":pipeline-application",
    "pipeline-protocol" to ":pipeline-protocol",
    "pipeline-scripting-api" to ":pipeline-scripting-api",
    "pipeline-scripting-kotlin24" to ":pipeline-scripting-kotlin24",
    "pipeline-testkit" to ":pipeline-testkit",
    "pipeline-events" to ":pipeline-events",
    "pipeline-step-sdk:api" to ":pipeline-step-sdk:api",
    "pipeline-step-sdk:processor" to ":pipeline-step-sdk:processor",
    "pipeline-step-sdk:runtime" to ":pipeline-step-sdk:runtime",
    "pipeline-binding-factory" to ":pipeline-binding-factory",
)

gradle.allprojects {
    val projPath = project.path
    if (projPath in v2ModulePaths.values) {
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

// Ensure :pipeline-architecture-tests:test runs after all capture tasks
val captureTaskPaths = v2ModulePaths.values.map { "$it:runtimeClasspathCapture" }
tasks.named("test") {
    dependsOn(captureTaskPaths)
}
