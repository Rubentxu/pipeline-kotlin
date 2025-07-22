plugins {
    kotlin("jvm") version "2.2.0" apply false
    id("dev.rubentxu.pipeline.steps") version "2.0-SNAPSHOT" apply false
}

allprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "dev.rubentxu.pipeline.steps")
    
    dependencies {
        "implementation"("dev.rubentxu.pipeline.core:core:1.0-SNAPSHOT")
    }
    
    configure<dev.rubentxu.pipeline.steps.gradle.StepsExtension> {
        enableCompilerPlugin = true
        enableAutoRegistration = true
        enableDslGeneration = true
    }
}