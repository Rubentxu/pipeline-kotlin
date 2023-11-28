plugins {
    id("org.jetbrains.kotlin.jvm")

}

val kotlinVersion: String by rootProject.extra
val kotlinCoroutinesVersion: String by rootProject.extra

group = "dev.rubentxu.pipeline.backend"
version = "1.0-SNAPSHOT"


dependencies {
    implementation(project(":core"))
    implementation(project(":pipeline-config"))

    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    implementation("org.freemarker:freemarker:2.3.32")

    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")

    implementation("com.github.docker-java:docker-java-core:3.3.4")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.3.4")


    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm:5.7.2")
    testImplementation("io.kotest:kotest-assertions-core-jvm:5.7.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("io.kotest:kotest-property-jvm:5.7.2")

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

    test {
        useJUnitPlatform()
    }
}


tasks.register("printClasspath") {
    doLast {
        configurations["runtimeClasspath"].forEach { println(it) }
    }
}
