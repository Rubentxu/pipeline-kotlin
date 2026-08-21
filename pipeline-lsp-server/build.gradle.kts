plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

application {
    mainClass.set("dev.rubentxu.pipeline.lsp.PipelineLspServerKt")
}

dependencies {
    // Core pipeline dependencies
    implementation(project(":core"))
    
    // LSP4J for Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.0")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.21.0")
    
    // Kotlin scripting for AST analysis
    implementation(libs.bundles.kotlin.scripting)
    implementation(libs.kotlin.compiler.embeddable)
    
    // Coroutines for async operations
    implementation(libs.kotlinx.coroutines.core)
    
    // Logging
    implementation(libs.logback.classic)
    implementation(libs.slf4j.api)
    
    // JSON handling
    implementation(libs.kotlinx.serialization.json)
    
    // Testing
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testImplementation(libs.mockk)
    testImplementation(libs.mockito.kotlin)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${libs.versions.kotlinx.coroutines.get()}")
    testImplementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc.debug:0.21.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}