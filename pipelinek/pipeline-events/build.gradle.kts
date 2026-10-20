plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `maven-publish`
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_0)
    }
}

dependencies {
    // `api`, not `implementation`, and the distinction is the whole point of this block.
    //
    // `implementation` publishes the dependency as `runtime` scope in the POM. A consumer that
    // compiles against a legal public type then fails. All three of these leak into the published
    // ABI and are named here, not guessed:
    //
    //   pipeline-domain         EventRef.source and PipelineEventEnvelope.subject are ResourceRef;
    //                            EventHistory and EventQuery take ResourceRef too.
    //   pipeline-scripting-api  DomainEvent's public properties are CacheKey and
    //                            List<ScriptingDiagnostic>.
    //   kotlinx-serialization   PipelineEventEnvelope and ProviderProvenance are @Serializable with
    //                            a KSerializer in their companion, so a consumer that DECODES an
    //                            envelope needs the serialization API to compile.
    api(project(":pipeline-domain"))
    api(project(":pipeline-scripting-api"))
    api(libs.kotlinx.serialization.json)
    // No explicit kotlin-stdlib declaration: the Kotlin JVM plugin already contributes it as an
    // `api` dependency, and `:pipeline-domain` relies on exactly that. Declaring it here as
    // `implementation` overrode that to `runtime` scope in the POM, so one contract would have
    // shipped with two different stdlib scopes depending on which module a consumer reached first.
    // BLOCK 2: `libs.sqlite.jdbc` is gone from this module, and that is the point of the split.
    // Nothing left here opens a connection — SqliteConnectionFactory, SqliteEventStore and
    // JsonEventLog moved to `:pipeline-events-store` — so a published event contract no longer
    // drags a JDBC driver onto a consumer's runtime classpath. A dependency that no code uses is
    // a claim about the artifact that nothing in the artifact can back.
    testImplementation(libs.kotlin.reflect)
    testImplementation(libs.junit.jupiter)
    // Override BOM-enforced wrong version (junit-platform-launcher uses 1.x not 5.x)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // `EventSchemaNoMapStringStringTest` shells out to `grep` over two source trees, and one of
    // them belongs to ANOTHER module. Gradle's up-to-date check only sees declared inputs, so
    // without this the task stayed UP-TO-DATE when the store's codecs changed and the guard kept
    // reporting the previous run's answer: a gate that had stopped guarding and still reported
    // green. Declared as an input, editing a codec re-runs the gate that reads it.
    inputs.dir(rootProject.layout.projectDirectory.dir("pipeline-events-store/src/main/kotlin"))
        .withPropertyName("eventPlaneStoreSources")
        .withPathSensitivity(PathSensitivity.RELATIVE)

    useJUnitPlatform()
}

// BLOCK 2: the published event contract. Consumers resolve it as ordinary Maven coordinates
// produced from THIS source revision, so `pipelinek-fabric` never needs a source or composite
// dependency on this repository. Version is not declared here: the root project is the single
// authority for it, and a per-module `version =` is a release-time defect.
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
