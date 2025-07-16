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
    // Core project for LocalPipelineContext and runtime dependencies
    // Using testImplementation to avoid GraalVM resolution issues in main compilation
    testImplementation(project(":core"))
    
    // Also add core as compileOnly for compiler plugin to access classes during transformation
    compileOnly(project(":core"))
    
    // Core K2 compiler dependencies for Kotlin 2.2+
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.compiler)
    
    // For ServiceLoader generation
    implementation(libs.google.autoservice)
    
    // Modern structured logging for compiler plugin
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    
    // ASM for deep bytecode analysis and verification
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    
    // Testing dependencies WITHOUT IntelliJ Platform
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test)
    
    // Testing approach focused on plugin functionality verification
    
    // Coroutines for testing async steps
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    useJUnitPlatform()
    
    // Configure for K2 testing
    systemProperty("kotlin.compiler.version", libs.versions.kotlin.get())
    systemProperty("kotlin.test.supportsK2", "true")
    
    // Performance testing configuration
    systemProperty("performance.tests", project.findProperty("runPerformanceTests") ?: "false")
    
    // More memory for compilation testing
    maxHeapSize = "2g"
    
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    // Filter tests for CI vs local development
    if (project.hasProperty("ci")) {
        exclude("**/*PerformanceTest*")
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.WARN
    
    manifest {
        attributes(
            "Implementation-Title" to "Pipeline Steps Compiler Plugin",
            "Implementation-Version" to project.version,
            "Implementation-Vendor" to "dev.rubentxu.pipeline",
            "Kotlin-Version" to libs.versions.kotlin.get(),
            "Supports-K2" to "true",
            "Context-Parameters-Support" to "true"
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("Pipeline Steps Compiler Plugin")
                description.set("Modern Kotlin compiler plugin for @Step functions with Context Parameters support")
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
            "-opt-in=org.jetbrains.kotlin.fir.extensions.ExperimentalFirExtensionApi",
            
            // Allow deprecated API usage for compatibility
            "-Xallow-unstable-dependencies",
            
            // Suppress deprecation warnings as errors for IR transformation development
            "-Xsuppress-version-warnings"
        )
    }
}

// Gradle task for running performance tests
tasks.register("performanceTest", Test::class) {
    description = "Runs performance tests for the compiler plugin"
    group = "verification"
    
    useJUnitPlatform()
    include("**/*PerformanceTest*")
    
    systemProperty("performance.tests", "true")
    maxHeapSize = "4g"
    
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}

