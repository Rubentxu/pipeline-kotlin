plugins {
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
    `java-test-fixtures`
    idea
}

group = "dev.rubentxu.pipeline.steps-system"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/kotlin"))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    testFixtures {
        java.setSrcDirs(listOf("test-fixtures"))
    }
    test {
        java.setSrcDirs(listOf("src/test/kotlin", "test-gen"))
        resources.setSrcDirs(listOf("testData"))
    }
}

idea {
    module.generatedSourceDirs.add(projectDir.resolve("test-gen"))
}

val annotationsRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }

dependencies {
    // Plugin annotations module for @Step and related annotations
    implementation(project(":pipeline-steps-system:plugin-annotations"))
    
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
    
    // Test fixtures for official Kotlin compiler test framework
    testFixturesApi(libs.junit.jupiter)
    testFixturesApi("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework")
    testFixturesApi("org.jetbrains.kotlin:kotlin-test-junit5")
    testFixturesApi(libs.kotlin.compiler)

    annotationsRuntimeClasspath(project(":pipeline-steps-system:plugin-annotations"))
    
    // Dependencies required to run the internal test framework
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly(libs.kotlin.reflect)
    testRuntimeOnly(libs.kotlin.test)
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-script-runtime")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-annotations-jvm")
    
    // Coroutines for testing async steps
    testImplementation(libs.kotlinx.coroutines.core)
}

tasks.test {
    dependsOn(annotationsRuntimeClasspath)

    useJUnitPlatform()
    workingDir = rootDir

    systemProperty("annotationsRuntime.classpath", annotationsRuntimeClasspath.asPath)

    // Properties required to run the internal test framework.
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")

    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)
    
    // Configure for K2 testing
    systemProperty("kotlin.compiler.version", libs.versions.kotlin.get())
    systemProperty("kotlin.test.supportsK2", "true")
    
    // More memory for compilation testing
    maxHeapSize = "2g"
    
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

fun Test.setLibraryProperty(propName: String, jarName: String) {
    val path = project.configurations
        .testRuntimeClasspath.get()
        .files
        .find { """$jarName-\d.*jar""".toRegex().matches(it.name) }
        ?.absolutePath
        ?: return
    systemProperty(propName, path)
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

val generateTests by tasks.registering(JavaExec::class) {
    inputs.dir(layout.projectDirectory.dir("testData"))
        .withPropertyName("testData")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.dir(layout.projectDirectory.dir("test-gen"))
        .withPropertyName("generatedTests")

    classpath = sourceSets.testFixtures.get().runtimeClasspath
    mainClass.set("dev.rubentxu.pipeline.steps.plugin.GenerateTestsKt")
    workingDir = rootDir
}

tasks.compileTestKotlin {
    dependsOn(generateTests)
}
