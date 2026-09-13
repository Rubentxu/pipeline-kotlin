plugins {
    kotlin("jvm")
    application
}

group = "dev.rubentxu.pipeline.v2"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

application {
    mainClass.set("dev.rubentxu.pipeline.v2.application.MainKt")
}

dependencies {
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-event-harness"))
    implementation(project(":pipeline-scripting-kotlin24"))
    implementation(project(":pipeline-scripting-api"))
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-step-sdk:files"))
    implementation(project(":pipeline-step-sdk:scm-git"))
    implementation(project(":pipeline-credentials-api"))
    implementation(project(":pipeline-credentials-local"))
    implementation(project(":pipeline-credentials-multipart")) // D2-rev: wire CredentialMaterializer for file-based credential dispatch
    implementation(project(":pipeline-credentials-executor")) // H0: port-driven withCredentials executor
    implementation(project(":pipeline-artefacts-local"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(project(":pipeline-step-sdk:scm-git"))
    // LB-02 / Lane R: external plugin under certification (example.uppercase) — the
    // same JAR the installed distribution hosts via --plugin-jar. Test classpath only.
    // Produced by :buildExamplePlugin from THIS revision's SDK; not a committed artifact.
    testImplementation(files(rootDir.resolve("../examples/example-uppercase-plugin/build/libs/example-uppercase-plugin-0.1.0.jar")))
    // Override BOM-enforced wrong version (junit-platform-launcher uses 1.x not 5.x)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

// Lane R: test *compilation* needs the plugin JAR on the test classpath, so the
// producer must be ordered before compileTestKotlin, not merely before test.
tasks.named("compileTestKotlin") { dependsOn(":buildExamplePlugin") }

tasks.test {
    dependsOn(":pipeline-application:installDist")
    dependsOn(":buildExamplePlugin")
    useJUnitPlatform()
}
