plugins {
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
}

group = "dev.rubentxu.pipeline.steps-system"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    // No dependencies needed for annotations
    // Keep annotations module as lightweight as possible
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    
    manifest {
        attributes(
            "Implementation-Title" to "Pipeline Steps Annotations",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "dev.rubentxu.pipeline",
            "Kotlin-Version" to libs.versions.kotlin.get()
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("Pipeline Steps Annotations")
                description.set("Annotations for Pipeline Steps system")
                url.set("https://github.com/rubentxu/pipeline-kotlin")
                
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
                
                developers {
                    developer {
                        id.set("rubentxu")
                        name.set("Rubén Porras")
                    }
                }
                
                scm {
                    connection.set("scm:git:git://github.com/rubentxu/pipeline-kotlin.git")
                    developerConnection.set("scm:git:ssh://github.com/rubentxu/pipeline-kotlin.git")
                    url.set("https://github.com/rubentxu/pipeline-kotlin")
                }
            }
        }
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
}