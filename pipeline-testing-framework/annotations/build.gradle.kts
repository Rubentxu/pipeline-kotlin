plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

group = "dev.rubentxu.pipeline.annotations"
version = "1.0.0"

dependencies {
    implementation(libs.kotlin.stdlib)
}

java {
    sourceCompatibility = JavaVersion.toVersion("21")
}

tasks {
    compileKotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }
}

// Configurar como biblioteca liviana sin dependencias adicionales
tasks.jar {
    archiveBaseName.set("pipeline-annotations")
    
    manifest {
        attributes(
            "Implementation-Title" to "Pipeline DSL Annotations",
            "Implementation-Version" to version,
            "Implementation-Vendor" to "dev.rubentxu"
        )
    }
}