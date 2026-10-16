// BQ1: pure impact / evidence / validation harness.
//
// The module is a leaf that owns no I/O. Its public surface is total
// functions over their inputs (ImpactResolver, EvidenceReader, ValidationPlan).
// The only side effects are inside XmlReportReader (filesystem + XML parser)
// and the catalogue IO surface is left to a future iteration; the contract
// surface here is deciders + their property tests.
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
    // No domain / runtime dependency: the deciders are pure total functions
    // over the ADTs declared in this module. The harness must be testable
    // without constructing the coordinator, the journal, or the event sink.
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.withType<Test>().configureEach {
    // The fast lane must run with fresh JVMs only when a test actually
    // mutates global state. The deciders here are pure, so a single JVM
    // is enough and parallel forks would be wasted overhead.
    maxParallelForks = 1
    forkEvery = 0
}

// BQ5: classify the test inventory without deleting anything. The
// classifier is pure; this task only drives the JAR with the inventory
// CSV as input and emits a JSON report. The DeletionGuard refuses every
// input because `retirement_authorised=false` (ADR-PK-QV-003 is still
// `proposed`, not `accepted`). To authorise, pass
// `-Ppkqv3.accepted=true` AND a recorded proof in the manifest.
val kotlinStdlib = configurations.runtimeClasspath.get().files.find { it.name.startsWith("kotlin-stdlib-") }
    ?: error("kotlin-stdlib not on the runtime classpath; cannot run the classifier CLI")
val classificationJson = rootProject.layout.buildDirectory.file("bq5-classification.json")
val jarTask = tasks.named<Jar>("jar")

tasks.register<JavaExec>("classifyInventory") {
    description = "BQ5: run the inventory classifier; emit a JSON report; never delete."
    group = "verification"
    classpath = files(jarTask.get().outputs.files, kotlinStdlib)
    mainClass.set("dev.rubentxu.pipeline.v2.harness.retirement.InventoryClassifierMainKt")
    dependsOn(jarTask)
    // The operator passes the inventory CSV path as a Gradle property
    // because the file is generated outside the v2 build tree (it lives
    // next to the worktree root, by the `tooling/inventory_tests.py`
    // script). Usage:
    //   ./gradlew :test-harness:classifyInventory -Ppk.inventoryCsv=$PWD/tmp/pipelinek-bq5-inventory.csv
    val input = providers.gradleProperty("pk.inventoryCsv").orNull
        ?: error("Pass -Ppk.inventoryCsv=/path/to/inventory.csv; run tooling/inventory_tests.py first.")
    val outputFile = classificationJson.get().asFile
    inputs.property("inventoryCsvPath", input)
    outputs.file(outputFile).withPropertyName("classificationJson")
    args(input, outputFile.absolutePath)
    doFirst {
        val f = file(input)
        if (!f.exists()) {
            throw GradleException("Inventory CSV not found at $input; run tooling/inventory_tests.py first.")
        }
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardError")
    }
}

// BQ2: explicit `validateFast` task that runs the test-harness deciders
// in-process. It does NOT depend on `:pipeline-application:installDist`
// or any of the `:buildExamplePlugin` family. The deciders here are pure
// total functions; no I/O; no subprocess. This is the lane a developer
// runs while iterating on a reducer, a codec, or a contract.
//
// The default `test` task already does this (it is the only test source
// in this module), so `validateFast` is an alias that documents intent
// and gives a name the CI matrix can target without ambiguity.
tasks.register<Test>("validateFast") {
    description = "Fast lane: in-process deciders, no installDist, no plugin build."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    testLogging {
        events("passed", "skipped", "failed", "standardError")
    }
}
