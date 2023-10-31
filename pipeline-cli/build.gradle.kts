plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("com.palantir.graal") version "0.9.0"
    application
}

val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra

group = "dev.rubentxu.pipeline.cli"
version = "1.0-SNAPSHOT"


kotlin {
    jvmToolchain(17)
}


dependencies {
    implementation(project(":core"))
//    implementation(project(":pipeline-script"))

    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3")
    implementation("org.gradle:gradle-tooling-api:8.4")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-script-util:1.8.22")


    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-main-kts:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-script-runtime:$kotlinCoroutinesVersion")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")

    testImplementation(kotlin("test"))

    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:5.7.2")

}

tasks.test {
    useJUnitPlatform()
}

tasks.test {
    useJUnitPlatform()
}

application {
    mainClass.set("dev.rubentxu.pipeline.cli.PipelineCliKt")
}

tasks {
    shadowJar {
        archiveClassifier.set("")
        manifest {
            attributes["Main-Class"] = "dev.rubentxu.pipeline.cli.PipelineCliKt"

        }
    }
}

// Define la tarea runShadowJar que ejecutará el JAR construido por shadowJar
val runPipeline by tasks.registering(JavaExec::class) {
    dependsOn("shadowJar")
    // Usa el JAR construido por shadowJar
    classpath = files(tasks.shadowJar.get().outputs.files.singleFile)
}

tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}
