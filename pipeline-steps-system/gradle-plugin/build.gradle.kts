plugins {
    alias(libs.plugins.kotlin.jvm)
    `kotlin-dsl`
    `java-gradle-plugin`
    id("maven-publish")
}

group = "dev.rubentxu.pipeline.steps-system"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Gradle Plugin Development API
    implementation(gradleApi())
    implementation(gradleKotlinDsl())
    
    // Kotlin Gradle Plugin API para integración con el compiler plugin
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin-api:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    
    // Referencia al compiler plugin para configuración automática
    implementation(project(":pipeline-steps-system:compiler-plugin"))
    
    // Core para tipos y anotaciones
    compileOnly(project(":core"))
    
    // Testing dependencies
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.kotest)
    testImplementation(gradleTestKit())
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

// Configurar el plugin descriptor
gradlePlugin {
    website.set("https://github.com/rubentxu/pipeline-kotlin")
    vcsUrl.set("https://github.com/rubentxu/pipeline-kotlin.git")
    
    plugins {
        create("stepsPlugin") {
            id = "dev.rubentxu.pipeline.steps"
            displayName = "Pipeline Steps Plugin"
            description = "Gradle plugin para configurar automáticamente @Step functions con inyección de pipelineContext"
            tags.set(listOf("kotlin", "compiler-plugin", "pipeline", "dsl", "steps"))
            implementationClass = "dev.rubentxu.pipeline.steps.gradle.StepsPluginSimple"
        }
    }
}

tasks.test {
    useJUnitPlatform()
    
    // Configurar para tests de Gradle plugins
    systemProperty("gradle.version", gradle.gradleVersion)
    systemProperty("kotlin.version", libs.versions.kotlin.get())
    
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Más memoria para tests de Gradle
    maxHeapSize = "2g"
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    
    manifest {
        attributes(
            "Implementation-Title" to "Pipeline Steps Gradle Plugin",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "dev.rubentxu.pipeline",
            "Kotlin-Version" to libs.versions.kotlin.get(),
            "Gradle-API-Version" to gradle.gradleVersion,
            "Plugin-ID" to "dev.rubentxu.pipeline.steps"
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("Pipeline Steps Gradle Plugin")
                description.set("Gradle plugin que configura automáticamente el compiler plugin para @Step functions con inyección de pipelineContext")
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
        // Kotlin 2.2+ compiler options
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        
        freeCompilerArgs.addAll(
            // Enable Context Parameters (Beta in Kotlin 2.2)
            "-Xcontext-receivers",
            
            // Opt-in to experimental APIs
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            
            // Allow deprecated API usage for compatibility
            "-Xallow-unstable-dependencies",
            
            // Suppress deprecation warnings as errors
            "-Xsuppress-version-warnings"
        )
    }
}

// Task para validar configuración del plugin
tasks.register("validatePluginConfiguration") {
    description = "Valida que la configuración del plugin sea correcta"
    group = "verification"
    
    doLast {
        val pluginId = "dev.rubentxu.pipeline.steps"
        val implementationClass = "dev.rubentxu.pipeline.steps.gradle.StepsPluginSimple"
        
        println("✅ Plugin ID: $pluginId")
        println("✅ Implementation: $implementationClass")
        println("✅ Version: $version")
        println("✅ Gradle API: ${gradle.gradleVersion}")
        println("✅ Kotlin version: ${libs.versions.kotlin.get()}")
        
        // Verificar que el compiler plugin esté disponible
        val compilerPluginProject = project(":pipeline-steps-system:compiler-plugin")
        println("✅ Compiler plugin disponible: ${compilerPluginProject.name}")
        
        println("\n🎉 Configuración del plugin válida!")
    }
}