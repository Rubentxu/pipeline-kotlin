plugins {
    kotlin("jvm") version "1.9.21"
    id("io.ktor.plugin") version "2.3.7"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.21"
}


val ktorVersion: String by project
val kotlinVersion: String by project
val logbackVersion: String by project
val kotlinCoroutinesVersion:  String by project
val kotestVersion:  String by project
val appVersion:  String by project
val koinVersion=  "3.5.2"

group = "dev.rubentxu.pipeline.backend"
version = appVersion


dependencies {
    implementation(project(":pipeline-dsl"))
    implementation(project(":pipeline-model"))

    implementation("ch.qos.logback:logback-classic:1.4.11")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$kotlinCoroutinesVersion")
    implementation("org.jetbrains.kotlin:kotlin-reflect:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:$kotlinVersion")

    implementation("org.freemarker:freemarker:2.3.32")

    implementation("org.jetbrains.kotlin:kotlin-scripting-jsr223:$kotlinVersion")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm:$kotlinVersion")

    implementation("com.github.docker-java:docker-java-core:3.3.4")
    implementation("com.github.docker-java:docker-java-transport-zerodep:3.3.4")

    // Ktor dependencies
    implementation("io.insert-koin:koin-ktor:3.5.1")
    implementation("io.insert-koin:koin-core:$koinVersion")

    implementation("io.rsocket.kotlin:rsocket-core:0.15.4")
    // TCP ktor client/server transport
    implementation("io.rsocket.kotlin:rsocket-transport-ktor-tcp:0.15.4")

    // WS ktor  transport
//    implementation("io.rsocket.kotlin:rsocket-transport-ktor-websocket-client:0.15.4")
//    implementation("io.rsocket.kotlin:rsocket-transport-ktor-websocket-server:0.15.4")

    // TCP nodeJS client/server transport
//    implementation("io.rsocket.kotlin:rsocket-transport-nodejs-tcp:0.15.4")


    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-property-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-extensions-junit5:$kotestVersion")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.1.0")
    testImplementation("uk.org.webcompere:system-stubs-jupiter:2.1.5")
    testImplementation("io.kotest:kotest-framework-datatest-jvm:5.8.0")
    testImplementation("io.insert-koin:koin-test:$koinVersion")
    testImplementation("io.insert-koin:koin-test-junit4:$koinVersion")
    testImplementation("io.insert-koin:koin-test-junit5:$koinVersion")

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
