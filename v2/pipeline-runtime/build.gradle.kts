plugins {
    kotlin("jvm")
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
    // `api`, not `implementation`: every published type in `:pipeline-runtime`
    // references values from `:pipeline-events` (e.g. `EventRecordReadPort`,
    // `EventTail` cursors) and from `:pipeline-output` (e.g. `OutputStreamId`,
    // `OutputTailState`). Hiding those behind `implementation` would put them at
    // `runtime` scope in the POM and hand a consumer a contract whose own value
    // types it cannot resolve at compile time — exactly the failure mode the
    // M1 read-side wires guard against.
    api(project(":pipeline-events"))
    api(project(":pipeline-output"))
    // `implementation`: the adapters compose internal PK stores (the SQLite
    // event store, the file-backed lease, the operation journal, the segment
    // output follower). The public ABI does not name any of these types, so
    // they belong at `implementation` scope: a consumer that pulls in
    // `:pipeline-runtime` does not transitively pull in JDBC drivers or the
    // file-backed lease lock.
    implementation(project(":pipeline-events-store"))
    implementation(project(":pipeline-output-store"))
    implementation(libs.kotlin.stdlib)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    // Required, not optional. Without it Gradle defaults to the JUnit 4 runner,
    // discovers zero JUnit 5 tests, and reports BUILD SUCCESSFUL with an empty
    // result set — a green gate that ran nothing. The M1-A test surface keeps
    // the same guard here.
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// M2 — the published runtime contract. An external consumer (Fabric) resolves
// `RuntimeIntrospectionPort`, `RuntimeControlPort`, `RuntimeRecoverPort` and
// their sealed ADTs as ordinary Maven coordinates from THIS source revision.
//
// The contract deliberately does not publish adapters (they live in the
// `:pipeline-runtime` module next to the ports, but are typed against internal
// `:pipeline-events-store` and `:pipeline-output-store` factories). A consumer
// that resolves `:pipeline-runtime` gets the ports; the wiring is the
// application-layer's job.
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
