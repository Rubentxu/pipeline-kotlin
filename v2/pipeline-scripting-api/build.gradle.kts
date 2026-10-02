plugins {
    kotlin("jvm")
    `maven-publish`
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        // S0-C1 (Pure Builder Consumption Gate): `@MustUseReturnValues` is an
        // "ignorability" annotation, and Kotlin 2.4 rejects it on a declaration
        // when the return-value checker is disabled (diagnostic
        // IGNORABILITY_ANNOTATIONS_WITH_CHECKER_DISABLED). The DSL module is
        // where the carriers are declared, so the checker is enabled here too.
        // This makes the library honest at its own boundary: an unconsumed
        // `PURE_BUILDER` result is an error for library consumers, not only
        // inside `.pipeline.kts`.
        freeCompilerArgs.add("-Xreturn-value-checker=check")
    }
}

dependencies {
    implementation(project(":pipeline-domain"))
    testImplementation(libs.junit.jupiter)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

tasks.test {
    useJUnitPlatform()
}

// Lane R: publish this SDK module into a build-local Maven repository so the
// independent external plugin build can compile against artifacts produced from
// THIS source revision. Replaces the committed libs/*.jar snapshots, which could
// silently drift from the live SDK.
publishing {
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "sdk"
            url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
        }
    }
}
