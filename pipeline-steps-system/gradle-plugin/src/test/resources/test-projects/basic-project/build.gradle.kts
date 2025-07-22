plugins {
    kotlin("jvm") version "2.2.0"
    id("dev.rubentxu.pipeline.steps") version "2.0-SNAPSHOT"
}

dependencies {
    implementation("dev.rubentxu.pipeline.core:core:1.0-SNAPSHOT")
}

steps {
    enableCompilerPlugin = true
    enableAutoRegistration = true
    enableDslGeneration = true
    enableDebugLogging = false
}