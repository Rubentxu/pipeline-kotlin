plugins {
    kotlin("jvm")
}

group = "dev.rubentxu.pipeline.v2"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
    }
}

dependencies {
    implementation(project(":pipeline-step-sdk:api"))
    implementation(project(":pipeline-step-sdk:runtime"))
    implementation(project(":pipeline-domain"))
    implementation(project(":pipeline-events"))
    implementation(project(":pipeline-scripting-api"))
}

// Unit-test wiring.
//
// The premise that once governed this block was that the module carries no unit tests:
// it is a workflow-control STEP whose subject is multi-step pipeline execution, verified by
// the executable E2E layer. That premise was correct about `execute()` and wrong about
// the module. `resolveTargetPath` is pure arithmetic on `java.nio.file.Path` --
// no filesystem, no clock, no subprocess -- and it
// is the confinement guard that decides whether a `dir(...)` scope may leave the workspace. It
// was therefore sitting untested behind a claim about a different function, and it was wrong:
// a `target.toString().startsWith(workspaceStr)` guard admitted every sibling directory whose
// name begins with the workspace root's. See `ResolveTargetPathTest`.
//
// So the premise is inverted rather than adopted blindly. These lines exist because a test
// source does, and `scripts/verify-all.sh` fails the build when a module with a test source
// produces no JUnit XML. Gradle 8.14 exposes no `failOnNoDiscoveredTests` property -- the
// setting does not exist in this version -- so the guarantee that makes this wiring safe lives
// in that script, not here.
dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}

tasks.test {
    useJUnitPlatform()
}
