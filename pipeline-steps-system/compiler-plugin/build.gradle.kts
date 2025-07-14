plugins {
    kotlin("jvm")
    kotlin("kapt")
}

dependencies {
    // Annotations module (lightweight dependency)
    implementation(project(":pipeline-steps-system:annotations"))
    
    // Core K2 compiler dependencies
    compileOnly(libs.kotlin.compiler.embeddable)
    
    // Para ServiceLoader generation
    implementation(libs.google.autoservice)
    kapt(libs.google.autoservice)
    
    // Testing
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.bundles.kotest)
    testImplementation(libs.kotlin.compiler.embeddable)
    testImplementation(libs.kotlin.test)
    
    // Kotlin compile testing for compiler plugin tests
    testImplementation("com.github.tschuchortdev:kotlin-compile-testing:1.6.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.WARN
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        freeCompilerArgs = listOf("-Xsuppress-deprecated-ir-api-usage")
    }
}