plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
    // WU-RP-040 R4: selective mutation (ReplayPolicy core). Run explicitly: :pipeline-domain:pitest
    id("info.solidsoft.pitest")
}

pitest {
    junit5PluginVersion = "1.2.1"
    targetClasses = listOf("dev.rubentxu.pipeline.v2.domain.durable.*")
    targetTests = listOf(
        "dev.rubentxu.pipeline.v2.domain.durable.*Test",
    )
    threads = System.getenv("PIT_THREADS")?.toInt() ?: 4
    timeoutConstInMillis = 10000
    outputFormats = listOf("XML", "HTML")
    mutators = listOf("DEFAULTS")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation("org.jetbrains.kotlin:kotlin-reflect:2.4.10")
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
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
