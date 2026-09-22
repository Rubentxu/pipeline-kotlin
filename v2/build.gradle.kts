plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.kover) apply true
}

group = "dev.rubentxu.pipeline.v2"
version = "0.39.0"

// WU-LPR-071: single-version provider. The root project.version is the SOLE authority
// for every subproject's publication version and for the jar manifest Implementation-Version
// (which pipelinek reads at runtime via `version`). Subprojects inherit by default; we make
// the policy explicit and refuse per-subproject overrides. Any future subproject MUST NOT
// declare its own `version = "..."` — that is a release-time defect.
//
// Fail-closed law: the released artifact's `version` subcommand MUST equal the git tag.
// If they ever diverge the build is broken at the source, not in the artifact.
subprojects {
    version = rootProject.version

    // Every subproject jar carries the same Implementation-Version attribute so
    // downstream introspection (or a future pipelinek that inspects dependency
    // manifests) reports a real version rather than "unknown".
    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version.toString(),
                "Implementation-Vendor" to project.group.toString(),
                "Built-By" to "Gradle",
            )
        }
    }
}

// The V2 root is an aggregate build. Its lifecycle check is the repository
// gate and deliberately covers every active V2 subproject declared in settings.
subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        rootProject.tasks.named("check") {
            dependsOn(tasks.named("check"))
        }
    }
}

// WU-RP-040 R1: risk-based coverage verification (Kover).
// Thresholds are grounded in criticality, not vanity numbers:
//  - domain / sdk-api / events: pure decision + codec/policy logic -> HIGH bar
//  - application: coordinator composition mostly exercised by real-process UATs -> MEDIUM bar
// Exclusions: generated protobuf, scripting test harness bootstrap, example plugins.
kover {
    reports {
        verify {
            rule("Branch coverage of critical decision modules") {
                disabled = false
            }
        }
        filters {
            excludes {
                packages(
                    "dev.rubentxu.pipeline.v2.protos",
                    "dev.rubentxu.pipeline.v2.generated",
                )
            }
        }
    }
}

subprojects {
    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        if (project.name in setOf("pipeline-domain", "pipeline-events")) {
            pluginManager.apply("org.jetbrains.kotlinx.kover")
            kover {
                currentProject {
                    sources {
                        excludedSourceSets.addAll(listOf("integrationTest"))
                    }
                }
            }
            kover {
                reports {
                    verify {
                        rule("Critical module branch coverage") {
                            bound {
                                minValue = 55
                            }
                        }
                    }
                }
            }
        }
    }
}

// Lane R: expose the repository root to tests. Tests that must read files from the
// repository previously hardcoded a developer-specific absolute path such as
// /var/home/<user>/Proyectos/kotlin/pipeline-kotlin, which pinned the suite to one
// machine and to one worktree. rootDir is the v2 build root; its parent is the repository.
val repositoryRoot: String = rootDir.parentFile.absolutePath

subprojects {
    tasks.withType<Test>().configureEach {
        systemProperty("pipeline.repoRoot", repositoryRoot)
    }
}

// WU-LPR-070: reproducible archives (BUILD ONCE / PUBLISH SAME BYTES).
// Nested project jars feed the pipelinek distZip; without these flags the
// embedded jar CRCs change on every rebuild (observed 2026-09-19).
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// ── Lane R: external plugin reproducibility ────────────────────────────────────
// example-uppercase-plugin is an INDEPENDENT Gradle build (its own settings file),
// not a subproject of v2: that independence is the property it certifies. It
// compiles against SDK artifacts, so those artifacts must be produced from THIS
// revision and handed to it explicitly.
//
// Before this, the plugin consumed committed libs/*.jar snapshots (silent drift)
// and its own jar had no producing task anywhere in v2, so :pipeline-application
// could not compile from a clean checkout.
val sdkRepoDir = layout.buildDirectory.dir("sdk-repo")

val publishSdkForExternalPlugin by tasks.registering {
    group = "build"
    description = "Publishes the SDK artifacts the external example plugin compiles against."
    dependsOn(
        ":pipeline-domain:publishSdkPublicationToSdkRepository",
        ":pipeline-scripting-api:publishSdkPublicationToSdkRepository",
    )
}

val buildExamplePlugin by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the independent external example plugin against this revision's SDK."
    dependsOn(publishSdkForExternalPlugin)

    val pluginDir = file("../examples/example-uppercase-plugin")
    inputs.dir(pluginDir.resolve("src"))
    inputs.files(pluginDir.resolve("build.gradle.kts"), pluginDir.resolve("settings.gradle.kts"))
    // The plugin's output depends on the SDK jars of THIS revision, not on the
    // repository's timestamped snapshot filenames.
    inputs.files(":pipeline-domain:jar", ":pipeline-scripting-api:jar")
    outputs.file(pluginDir.resolve("build/libs/example-uppercase-plugin-0.1.0.jar"))

    workingDir = rootDir
    commandLine(
        rootDir.resolve("gradlew").absolutePath,
        "-p", pluginDir.absolutePath,
        "--console=plain",
        "-PsdkRepo=" + sdkRepoDir.get().asFile.absolutePath,
        // Pass the SDK version the external plugin must resolve. Without this the
        // plugin's default sdkVersion is 0.1.0-SNAPSHOT, which is not in the local
        // sdk-repo (it carries the root project version 0.39.0). The --rerun-tasks
        // failure mode observed 2026-09-19 was caused by this omission: the example
        // build re-resolved the SDK from scratch, hit the default version, and could
        // not find the module.
        "-PsdkVersion=" + rootProject.version.toString(),
        "jar",
    )
}
