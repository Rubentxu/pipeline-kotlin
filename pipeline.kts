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
// Capabilities used (verified against PipelineDsl.kt): pipeline, stages, stage,
// echo, sh, dir, whenCondition, archiveArtifacts, cleanWs, agent, options,
// post, always, ansiColor, timestamps.
//
// GitHub Actions contract: the GA workflow is ONLY a thin shell that triggers
// this script. It MUST NOT duplicate test selection, certification, artifact
// selection, release sequencing, or version rules. The pipeline.kts is the
// single source of truth for those decisions.

// WU-LPR-071: projectVersion is the single authority for the release under
// construction. The Gradle root build (v2/build.gradle.kts) carries the same
// value; both MUST be updated together at release time. Keeping it top-level
// so any sh() can interpolate it.
val PKG_VERSION = "0.39.0"
val DIST = "v2/pipeline-application/build/distributions/pipelinek-${'$'}PKG_VERSION.zip"

pipeline {
    stages {

        stage("Validate") {
            agent("linux")
            options {
                timeout(1800)
                retry(2)
            }
            ansiColor("xterm") {
                timestamps {
                    echo("pipelinek CI/CD root — Validate")
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

        stage("Publish") {
            // Opt-in: only when LPR_PUBLISH=true is set in the environment.
            whenCondition("env.LPR_PUBLISH == 'true'") {
                echo("pipelinek CI/CD root — Publish (opt-in via LPR_PUBLISH=true)")
                archiveArtifacts("v2/pipeline-application/build/distributions/pipelinek-*.zip")
                archiveArtifacts("v2/pipeline-application/build/distributions/pipelinek-*.zip.sha256")
            }
        }

        stage("Post-publish Smoke") {
            whenCondition("env.LPR_PUBLISH == 'true'") {
                echo("pipelinek CI/CD root — Post-publish smoke (placeholder; real smoke is the GitHub-Release asset download step in WU-LPR-071)")
            }
        }

    }
}
