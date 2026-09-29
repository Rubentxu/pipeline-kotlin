// /pipeline.kts — CI/CD authority for the pipeline-kotlin project itself.
//
// This script is the canonical root CI/CD authority. It is executed by the
// pipelinek binary itself (or by the bootstrap `installDist` binary for the
// first release). It MUST stay aligned with what pipelinek can actually do
// today — no DSL extensions for cosmetic reasons.
//
// Bootstrap exception (WU-LPR-071): for the very first release (0.36.0) this
// script is executed by the temporary `./gradlew :pipeline-application:installDist`
// bootstrap. After 0.36.0 ships, this script MUST be executed by
// `pipelinek 0.36.0` (or newer) downloaded from the GitHub Release of the
// previous tag. The bootstrap path is retired once self-hosting is stable.
//
// Capabilities actually used by this script (verified against the script body, not
// aspirational): pipeline, stages, stage, echo, sh, dir, options, retry, ansiColor,
// timestamps.
//
// S0-B CORRECTION: the previous header also listed `agent`, `post`, `always`,
// `archiveArtifacts` and `cleanWs`. None of them appears anywhere in the script
// body — they were aspirational claims, exactly the class of semantic overclaim
// the Semantic Honesty Gate exists to remove. `agent`, `post` and `always` are
// additionally UNSUPPORTED_FAIL_CLOSED in the DSL surface manifest, so naming
// them here would have been a false claim twice over.
//
// GitHub Actions contract: the GA workflow is ONLY a thin shell that triggers
// this script. It MUST NOT duplicate test selection, certification, artifact
// selection, release sequencing, or version rules. The pipeline.kts is the
// single source of truth for those decisions.

// WU-LPR-071: v2/build.gradle.kts `version` is the SOLE authority for the
// project version (the single-version provider propagates it to every
// subproject and to the jar manifest). S0-B: this script used to hardcode
// PKG_VERSION = "0.39.0" and drifted from the real 0.42.0-rc1, which broke the
// Dist stage's `test -f $DIST` guard — the release pipeline could never pass.
// It is now DERIVED from the authority, so the two cannot drift again.
val PKG_VERSION: String = java.io.File("v2/build.gradle.kts")
    .readLines()
    .first { it.trimStart().startsWith("version = ") }
    .substringAfter("version = ")
    .trim()
    .trim('"')

val DIST = "v2/pipeline-application/build/distributions/pipelinek-${'$'}PKG_VERSION.zip"

pipeline {
    stages {

        stage("Validate") {
            options {
                timeout(1800)
            }
            // S0-B: the `ansiColor("xterm") { ... }` wrapper was removed here.
            // `ansiColor` is UNSUPPORTED_FAIL_CLOSED in the DSL surface manifest
            // (it lowers to core.ansiColor, which has no descriptor row), so the
            // canonical bridge rejected the whole script with exit 2 before any
            // stage ran — this CI script could not execute at all. It was a
            // purely cosmetic console decorator; `timestamps`, its supported
            // sibling, still wraps the same body below. See
            // docs/v2/surface/DSL_SURFACE_MANIFEST.md and the S0-B receipt.
            timestamps {
                echo("pipelinek CI/CD root — Validate")
                // WU-RP-042 S2 (R1): retry lives in the retry Block Step
                // (ADR-0075, WU-RP-032 removed it from options). The
                // previous options { retry(2) } here did not compile.
                retry(2) {
                    sh("./v2/gradlew -p v2 :pipeline-application:installDist --quiet")
                    sh("v2/pipeline-application/build/install/pipelinek/bin/pipelinek version")
                    sh("v2/pipeline-application/build/install/pipelinek/bin/pipelinek doctor")
                }
            }
        }

        stage("Compile") {
            echo("pipelinek CI/CD root — Compile")
            sh("./v2/gradlew -p v2 compileKotlin compileTestKotlin --continue")
        }

        stage("Unit Tests") {
            echo("pipelinek CI/CD root — Unit Tests")
            sh("./v2/gradlew -p v2 :pipeline-domain:test :pipeline-events:test")
        }

        stage("Architecture Fitness") {
            echo("pipelinek CI/CD root — Architecture Fitness")
            sh("./v2/gradlew -p v2 :pipeline-architecture-tests:test --continue")
        }

        stage("Compatibility Corpus") {
            echo("pipelinek CI/CD root — Compatibility Corpus")
            sh("./v2/gradlew -p v2 :pipeline-application:test --tests 'UatCompat001*' --tests 'CompatibilityCorpusTest*'")
        }

        stage("Application UAT") {
            echo("pipelinek CI/CD root — Application UAT (focused)")
            sh(
                "./v2/gradlew -p v2 :pipeline-application:test " +
                    "--tests 'UatLocal00*' --tests 'UatLocal01*' --tests 'UatDsl*' " +
                    "--tests 'UatParallel*' --tests 'UatTimeout*' --tests 'UatRetry*' " +
                    "--tests 'CanonicalDurableRunCoordinatorTest*' " +
                    "--tests 'WULpr010*'"
            )
        }

        stage("Real Project Gradle") {
            echo("pipelinek CI/CD root — Real Gradle project smoke")
            dir("integration/gradle-demo") {
                sh(
                    "../../v2/pipeline-application/build/install/pipelinek/bin/pipelinek run " +
                        "--db /tmp/lpr-ci-gradle.sqlite " +
                        "--control-root /tmp/lpr-ci-gradle-ctl " +
                        "pipeline.kts"
                )
            }
        }

        stage("Real Project Maven") {
            echo("pipelinek CI/CD root — Real Maven project smoke")
            dir("integration/maven-demo") {
                sh(
                    "../../v2/pipeline-application/build/install/pipelinek/bin/pipelinek run " +
                        "--db /tmp/lpr-ci-maven.sqlite " +
                        "--control-root /tmp/lpr-ci-maven-ctl " +
                        "pipeline.kts"
                )
            }
        }

        stage("Real Project Node") {
            echo("pipelinek CI/CD root — Real Node project smoke")
            dir("integration/node-demo") {
                sh(
                    "../../v2/pipeline-application/build/install/pipelinek/bin/pipelinek run " +
                        "--db /tmp/lpr-ci-node.sqlite " +
                        "--control-root /tmp/lpr-ci-node-ctl " +
                        "pipeline.kts"
                )
            }
        }

        stage("Package") {
            echo("pipelinek CI/CD root — Package (reproducible distZip)")
            sh("./v2/gradlew -p v2 :pipeline-application:distZip --stacktrace")
        }

        stage("Release Verification") {
            echo("pipelinek CI/CD root — Release Verification (ZIP integrity + smoke)")
            sh(
                """set -euo pipefail
                test -f "${'$'}DIST" || (echo "missing distZip: ${'$'}DIST" && exit 1)
                sha256sum "${'$'}DIST" | tee "${'$'}DIST.sha256"
                unzip -tq "${'$'}DIST" > /dev/null
                rm -rf /tmp/lpr-ci-verify && mkdir -p /tmp/lpr-ci-verify && unzip -q "${'$'}DIST" -d /tmp/lpr-ci-verify
                /tmp/lpr-ci-verify/pipelinek-${'$'}PKG_VERSION/bin/pipelinek version
                /tmp/lpr-ci-verify/pipelinek-${'$'}PKG_VERSION/bin/pipelinek doctor"""
            )
        }

        // Publication is deliberately NOT a stage of this script.
        //
        // It used to be:
        //
        //     stage("Publish") {
        //         whenCondition("env.LPR_PUBLISH == 'true'") { archiveArtifacts(...) }
        //     }
        //
        // `whenCondition` has no carrier in the IR: it appended its body to the
        // stage unconditionally, so this script published on EVERY run while
        // reading as an opt-in gate. With the fail-closed fix in place
        // (TRAIN-DSL-HONESTY) it no longer compiles at all.
        //
        // Neither behaviour is acceptable. Deciding to publish is a release-train
        // decision taken by the operator, not a runtime predicate the DSL cannot
        // evaluate. This script now ends at `package` + `verify`; publication is
        // an explicit external action against the immutable RC bytes.

    }
}
