plugins {
    kotlin("jvm") version "1.9.21"
    id("com.google.devtools.ksp") version "1.9.20-1.0.14"
    id("com.github.johnrengelman.shadow") version "8.1.1"
    application
}

val ktorVersion: String by project
val kotlinVersion: String by project
val logbackVersion: String by project
val kotlinCoroutinesVersion:  String by project
val kotestVersion:  String by project
val appVersion:  String by project

group = "dev.rubentxu.pipeline.cli"
version = appVersion

application {
    mainClass.set("dev.rubentxu.pipeline.cli.PipelineCliCommand")
}

dependencies {
    implementation(project(":pipeline-dsl"))
    implementation(project(":pipeline-core"))
//    implementation(project(":pipeline-backend"))


    implementation("info.picocli:picocli-codegen:4.6.3")
    implementation("info.picocli:picocli:4.6.3")
    implementation("ch.qos.logback:logback-core:1.4.11")
    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("org.slf4j:slf4j-api:1.7.30")

//
//    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
//    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
//    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:${kotestVersion}")
    testImplementation("io.kotest:kotest-assertions-core:${kotestVersion}")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:${kotestVersion}")

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

}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}


tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}
