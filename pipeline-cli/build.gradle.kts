plugins {
    kotlin("jvm")
    id("io.kotest") version "0.4.10"
    id("com.github.johnrengelman.shadow") version "7.1.2"
    id("com.palantir.graal") version "0.9.0"
//    application
}

val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra

group = "dev.rubentxu.pipeline.cli"
version = "1.0-SNAPSHOT"


dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-script"))

    implementation("com.github.ajalt.clikt:clikt:4.2.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.15.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.15.3")
    implementation("org.gradle:gradle-tooling-api:8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-common:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host:$kotlinVersion")


    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")
//    testRuntimeOnly("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")
}

tasks.test {
    useJUnitPlatform()
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
