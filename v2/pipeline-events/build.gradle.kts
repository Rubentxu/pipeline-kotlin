plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
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
    implementation(libs.kotlin.stdlib)
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-scripting-api"))
    implementation(libs.kotlinx.serialization.json)
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
