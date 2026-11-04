plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-credentials-api"))

    // The integration lane composes the real store with the real materializer.
    // A credential's bytes reach the materializer through a store, and a
    // materializer wired to a fiction of a store says nothing about the pair:
    // the store decides what a credential IS, and the materializer decides where
    // its bytes land. The store brings its own cryptography with it.
    integrationTestImplementation(project(":pipeline-credentials-local"))
}

// The unit lane covers the decisions that are pure functions of their
// arguments: where a `Zip` entry may be written, and which directories a
// nested path creates. The decisions that need a filesystem — writing the
// credential out, and refusing an entry that leaves its directory — are the
// integration lane's, because the unit rule forbids both.
// The JUnit dependency, the launcher and the `useJUnitPlatform()` block travel
// together: a `Test` task whose source set is empty reports success while
// executing zero classes, and Gradle 8.14 exposes no `failOnNoDiscoveredTests`
// property -- the setting does not exist in this version, so nothing inside
// the build can turn that green into a failure, and a build file asserting
// otherwise is describing a flag it cannot compile.
// What turns it into a failure lives outside Gradle: `scripts/verify-all.sh`
// enumerates the modules it asks from the filesystem and fails when a module
// that has a test source produces no JUnit XML.

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
