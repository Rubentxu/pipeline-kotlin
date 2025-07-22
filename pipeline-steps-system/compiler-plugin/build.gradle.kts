plugins {
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
    idea
}

group = "dev.rubentxu.pipeline.steps-system"
version = "2.0-SNAPSHOT"

repositories {
    mavenCentral()
}

// ============================================================================
// SOURCE SETS CONFIGURATION
// ============================================================================

sourceSets {
    main {
        java.setSrcDirs(listOf("src/main/kotlin"))
        resources.setSrcDirs(listOf("src/main/resources"))
    }
    test {
        java.setSrcDirs(listOf("src/test/kotlin", "test-gen"))
        resources.setSrcDirs(listOf("src/test/resources"))
    }
}

// ============================================================================
// INTELLIJ IDEA CONFIGURATION
// ============================================================================

idea {
    module {
        generatedSourceDirs.add(projectDir.resolve("test-gen"))
        testSources.from(file("src/test/kotlin"))
        testResources.from(file("src/test/resources"))
        excludeDirs.addAll(files("build", ".gradle", "out"))
    }
}

// ============================================================================
// DEPENDENCIES
// ============================================================================

val annotationsRuntimeClasspath: Configuration by configurations.creating { isTransitive = false }

dependencies {
    implementation(libs.google.autoservice)
    
    // Kotlin compiler K2 API
    compileOnly(libs.kotlin.compiler.embeddable)
    compileOnly(libs.kotlin.compiler)
    
    // Logging (structured logging for plugin)
    implementation("io.github.oshai:kotlin-logging-jvm:7.0.0")
    implementation("ch.qos.logback:logback-classic:1.5.12")
    
    // Bytecode analysis
    implementation("org.ow2.asm:asm:9.6")
    implementation("org.ow2.asm:asm-util:9.6")
    implementation("org.ow2.asm:asm-commons:9.6")
    
    // Annotations for test classpath
    annotationsRuntimeClasspath(project(":core"))
    
    // Test runtime dependencies
    testRuntimeOnly("junit:junit:4.13.2")
    testRuntimeOnly(libs.kotlin.reflect)
    testRuntimeOnly(libs.kotlin.test)
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-script-runtime")
    testRuntimeOnly("org.jetbrains.kotlin:kotlin-annotations-jvm")
    
    // Test fixtures provide basic context classes for testing
    
    // Kotlin compiler testing framework for BDD tests  
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlin:kotlin-compiler-internal-test-framework")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(libs.kotlin.compiler)
    
    // Test implementation
    testImplementation(project(":core"))
    testImplementation(libs.kotlinx.coroutines.core)
    
    // Kotest BDD testing framework (optimized for IntelliJ)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotest.framework.datatest)
}

// ============================================================================
// KOTLIN COMPILATION CONFIGURATION
// ============================================================================

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_2)
        
        freeCompilerArgs.addAll(
            "-Xcontext-receivers",
            "-opt-in=kotlin.RequiresOptIn",
            "-opt-in=org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi",
            "-opt-in=org.jetbrains.kotlin.fir.extensions.ExperimentalFirExtensionApi",
            "-Xallow-unstable-dependencies",
            "-Xsuppress-version-warnings"
        )
    }
}

// ============================================================================
// TESTING CONFIGURATION
// ============================================================================

// Helper function for setting Kotlin library system properties
fun Test.setKotlinLibraryProperty(propName: String, jarName: String) {
    val path = project.configurations.testRuntimeClasspath.get().files
        .find { """$jarName-\d.*jar""".toRegex().matches(it.name) }
        ?.absolutePath ?: return
    systemProperty(propName, path)
}

// Common test configuration
fun Test.configureCommonTestSettings() {
    useJUnitPlatform {
        includeEngines("kotest", "junit-jupiter")
        excludeEngines("junit-vintage")
    }
    
    maxHeapSize = "2g"
    workingDir = rootDir
    
    // Kotlin compiler test framework properties
    setKotlinLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib", "kotlin-stdlib")
    setKotlinLibraryProperty("org.jetbrains.kotlin.test.kotlin-stdlib-jdk8", "kotlin-stdlib-jdk8")
    setKotlinLibraryProperty("org.jetbrains.kotlin.test.kotlin-reflect", "kotlin-reflect")
    setKotlinLibraryProperty("org.jetbrains.kotlin.test.kotlin-test", "kotlin-test")
    setKotlinLibraryProperty("org.jetbrains.kotlin.test.kotlin-script-runtime", "kotlin-script-runtime")
    setKotlinLibraryProperty("org.jetbrains.kotlin.test.kotlin-annotations-jvm", "kotlin-annotations-jvm")
    
    // System properties for compiler plugin testing
    systemProperty("annotationsRuntime.classpath", annotationsRuntimeClasspath.asPath)
    systemProperty("kotlin.compiler.version", libs.versions.kotlin.get())
    systemProperty("kotlin.test.supportsK2", "true")
    systemProperty("idea.ignore.disabled.plugins", "true")
    systemProperty("idea.home.path", rootDir)
    
    // Kotest configuration for optimal IntelliJ IDEA integration
    systemProperty("kotest.framework.classpath.scanning.autoscan.disable", "true")
    systemProperty("kotest.framework.parallelism", "1")
    systemProperty("kotest.tags", "")
    
    // JVM arguments for Java module system compatibility
    jvmArgs(
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED"
    )
    
    testLogging {
        events("passed", "skipped", "failed")
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
}

// Main test task
tasks.test {
    dependsOn(annotationsRuntimeClasspath, tasks.jar, ":core:jar")
    
    configureCommonTestSettings()
    
    doFirst {
        val pluginJar = tasks.jar.get().archiveFile.get().asFile
        val annotationsJar = project(":core")
            .tasks.named("jar", Jar::class.java).get().archiveFile.get().asFile

        systemProperty("plugin.jar.path", pluginJar.absolutePath)
        systemProperty("annotations.jar.path", annotationsJar.absolutePath)

        val testClasspath = configurations.testRuntimeClasspath.get().files
            .joinToString(File.pathSeparator) { it.absolutePath }
        systemProperty("test.classpath", testClasspath)

        println("🔧 Test configuration:")
        println("   - Plugin JAR: ${pluginJar.exists()} -> ${pluginJar.name}")
        println("   - Annotations JAR: ${annotationsJar.exists()} -> ${annotationsJar.name}")
        println("   - Test classpath entries: ${testClasspath.split(File.pathSeparator).size}")
    }
}

// Helper task to run only BDD tests
tasks.register("testBDD") {
    group = "verification"
    description = "Run only Kotest BDD tests"
    
    doLast {
        println("🧪 Running BDD tests...")
        println("Usage: gradle test --tests=\"*BDD*\"")
    }
}

// ============================================================================
// BUILD CONFIGURATION
// ============================================================================

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

// Test generation disabled - comprehensive BDD tests cover all functionality

// ============================================================================
// PUBLISHING
// ============================================================================

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