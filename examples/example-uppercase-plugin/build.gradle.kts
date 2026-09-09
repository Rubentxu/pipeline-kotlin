plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.serialization") version "2.4.10"
}

repositories { mavenCentral() }

group = "example.uppercase"
version = "0.1.0"

// INDEPENDENT BUILD (EP-3): separate Gradle boundary. It depends ONLY on public
// SDK artifacts (pipeline-domain jar: StepDefinitionContributor/StepRegistry
// contracts + domain value types). No internal application/runtime imports.
// The DSL facade (EP-5) needs pipeline-scripting-api for StepsScope/registryStep.
dependencies {
    compileOnly(files("libs/pipeline-domain-0.1.0-SNAPSHOT.jar"))
    compileOnly(files("libs/pipeline-scripting-api-0.1.0-SNAPSHOT.jar"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

kotlin {
    jvmToolchain(21)
}

tasks.jar {
    manifest {}
    // Nothing else: the plugin JAR carries only its own classes. The ServiceLoader
    // descriptor (EP-4) is a resource; the SDK contracts come from the host at runtime.
}
