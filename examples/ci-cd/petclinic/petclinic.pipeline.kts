// A real Maven project is built, tested, published and raised in an ephemeral staging.
//
// This pipeline shows what a complete CI/CD run of PipelineK looks like: a Spring PetClinic build
// at a pinned commit, its test suites separated, the coverage measured against a declared floor,
// the reports of the run archived, the application published as an OCI image by its digest, and
// that digest raised in a staging namespace that holds nothing else and leaves nothing behind.
//
// **The suites are separated, and each one says what it did.** The project carries three of them:
// the unit tests, the integration tests that run against an in-memory database, and the tests
// that need a container engine. **The third one is not run in a worker**, and a worker that
// cannot run it says so: the summary of a suite that was skipped is a suite that did not run, and
// a build that called it a pass would be calling a suite that never executed a pass.
//
// **The source is fixed.** The commit is named by the template and the run says which commit it
// built: a pipeline that measured whatever the branch held today would be measuring a moving
// target.
//
// **The coverage is a number, and the threshold is declared.** The report of the coverage says
// how many lines were covered and how many were not, the template declares the floor it accepts,
// and a coverage under that floor ends the run.
//
// **The package runs the whole verification.** The report of the coverage is written by the build
// that runs the tests, so the stage that packages what was built runs every suite that can run —
// the ones that need a container engine are left out, because a suite that is left out is a suite
// that did not run — and the coverage that comes out is the coverage of the tests that ran.
//
// **The manifests are JSON, and they are written here and nowhere else.** The staging is
// described by a document this file holds, and the document is JSON: the cluster reads it, and so
// does whoever reads the console.
//
// **The image is deployed by its digest.** The digest is read of the registry after the
// publication, and the deployment names the digest and not the tag: a tag is a name somebody can
// move.
//
// The values a deployment changes — where the registry is, which commit is built, what the
// coverage floor is — come from the environment the worker is born with, which the fleet
// template declares. See the README beside this file for the template values and how to create
// the job.
pipeline {
    stages {
        stage("prepare") {
            sh("""
                set -eu
                mkdir -p "${'$'}LAB_DIR/home"
                cat > "${'$'}LAB_DIR/env.sh" <<'HELPER'
                #!/bin/sh
                # The home of the build is in the volume the deployment granted. The root of
                # the image is read-only: a Maven that wrote its distribution under the home of
                # the user would fail on a home it cannot write.
                export HOME=${'$'}LAB_DIR/home
                export MAVEN_USER_HOME=${'$'}LAB_DIR/home/.m2
                HELPER
                . "${'$'}LAB_DIR/env.sh"
                cd "${'$'}LAB_DIR"
                rm -rf project
                git clone --quiet "${'$'}APP_SOURCE_URL" project
                cd project
                git checkout --quiet "${'$'}APP_SOURCE_COMMIT"
                echo source-commit=${'$'}(git rev-parse HEAD)
                cat > "${'$'}LAB_DIR/summary.sh" <<'HELPER'
                #!/bin/sh
                # The counts of a surefire log: the tests, the failures, the errors and the skipped.
                # The totals line is the one without a time: the line of every class carries one.
                log="$1"
                line=${'$'}(grep -E '^\[INFO\] Tests run: [0-9]+, Failures: [0-9]+, Errors: [0-9]+, Skipped: [0-9]+${'$'}' "${'$'}log" | tail -1)
                if [ -z "${'$'}line" ]; then
                  echo "the log says nothing about the tests" >&2
                  exit 1
                fi
                echo "${'$'}line" | sed -E 's/^\[INFO\] Tests run: ([0-9]+), Failures: ([0-9]+), Errors: ([0-9]+), Skipped: ([0-9]+)$/\1 \2 \3 \4/'
                HELPER
                chmod +x "${'$'}LAB_DIR/summary.sh"
            """.trimIndent())
        }
        stage("quality") {
            sh("""
                set -eu
                . "${'$'}LAB_DIR/env.sh"
                cd "${'$'}LAB_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}LAB_DIR/m2" validate > "${'$'}LAB_DIR/quality.log" 2>&1; then
                  tail -40 "${'$'}LAB_DIR/quality.log"
                  exit 1
                fi
                echo quality=ok
            """.trimIndent())
        }
        stage("unit") {
            sh("""
                set -eu
                . "${'$'}LAB_DIR/env.sh"
                cd "${'$'}LAB_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}LAB_DIR/m2" test -Dtest="!*IntegrationTests" -DfailIfNoTests=false > "${'$'}LAB_DIR/unit.log" 2>&1; then
                  tail -40 "${'$'}LAB_DIR/unit.log"
                  exit 1
                fi
                summary=$(sh "${'$'}LAB_DIR/summary.sh" "${'$'}LAB_DIR/unit.log")
                set -- ${'$'}summary
                echo unit-tests=${'$'}1 unit-failures=${'$'}2 unit-errors=${'$'}3 unit-skipped=${'$'}4
                if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                  echo "the unit suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}1" = "0" ]; then
                  echo "the unit suite ran no test at all" >&2
                  exit 1
                fi
                mkdir -p "${'$'}LAB_DIR/reports/unit"
                cp "${'$'}LAB_DIR/project"/target/surefire-reports/TEST-*.xml "${'$'}LAB_DIR/reports/unit/"
            """.trimIndent())
        }
        stage("integration") {
            sh("""
                set -eu
                . "${'$'}LAB_DIR/env.sh"
                cd "${'$'}LAB_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}LAB_DIR/m2" test -Dtest="PetClinicIntegrationTests,CrashControllerIntegrationTests" -DfailIfNoTests=false > "${'$'}LAB_DIR/integration.log" 2>&1; then
                  tail -40 "${'$'}LAB_DIR/integration.log"
                  exit 1
                fi
                summary=$(sh "${'$'}LAB_DIR/summary.sh" "${'$'}LAB_DIR/integration.log")
                set -- ${'$'}summary
                echo integration-tests=${'$'}1 integration-failures=${'$'}2 integration-errors=${'$'}3 integration-skipped=${'$'}4
                if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                  echo "the integration suite failed" >&2
                  exit 1
                fi
                if [ "${'$'}1" = "0" ]; then
                  echo "the integration suite ran no test at all" >&2
                  exit 1
                fi
                mkdir -p "${'$'}LAB_DIR/reports/integration"
                cp "${'$'}LAB_DIR/project"/target/surefire-reports/TEST-*.xml "${'$'}LAB_DIR/reports/integration/"
            """.trimIndent())
        }
        stage("containers") {
            sh("""
                set -eu
                . "${'$'}LAB_DIR/env.sh"
                cd "${'$'}LAB_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}LAB_DIR/m2" test -Dtest="MySqlIntegrationTests" -DfailIfNoTests=false > "${'$'}LAB_DIR/containers.log" 2>&1; then
                  tail -40 "${'$'}LAB_DIR/containers.log"
                  exit 1
                fi
                summary=$(sh "${'$'}LAB_DIR/summary.sh" "${'$'}LAB_DIR/containers.log")
                set -- ${'$'}summary
                echo containers-tests=${'$'}1 containers-failures=${'$'}2 containers-errors=${'$'}3 containers-skipped=${'$'}4
                if [ "${'$'}2" != "0" ] || [ "${'$'}3" != "0" ]; then
                  echo "the suite of the containers failed" >&2
                  exit 1
                fi
                if [ "${'$'}4" = "${'$'}1" ] && [ "${'$'}1" != "0" ]; then
                  if grep -q "Could not find a valid Docker environment" "${'$'}LAB_DIR/containers.log"; then
                    reason=no-container-engine
                  else
                    reason=the-suite-skipped-its-tests
                  fi
                  echo containers-suite=NOT_RUN containers-reason=${'$'}reason
                else
                  echo containers-suite=RAN containers-reason=none
                fi
                mkdir -p "${'$'}LAB_DIR/reports/containers"
                cp "${'$'}LAB_DIR/project"/target/surefire-reports/TEST-*.xml "${'$'}LAB_DIR/reports/containers/"
            """.trimIndent())
        }
        stage("package") {
            sh("""
                set -eu
                . "${'$'}LAB_DIR/env.sh"
                cd "${'$'}LAB_DIR/project"
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}LAB_DIR/m2" verify -Dtest="!MySqlIntegrationTests" -DfailIfNoTests=false > "${'$'}LAB_DIR/package.log" 2>&1; then
                  tail -40 "${'$'}LAB_DIR/package.log"
                  exit 1
                fi
                if ! ./mvnw -B -Dmaven.repo.local="${'$'}LAB_DIR/m2" org.cyclonedx:cyclonedx-maven-plugin:makeAggregateBom > "${'$'}LAB_DIR/sbom.log" 2>&1; then
                  tail -40 "${'$'}LAB_DIR/sbom.log"
                  exit 1
                fi
                test -f target/site/jacoco/jacoco.xml
                test -f target/bom.json
                ls -l target/*.jar
            """.trimIndent())
        }
        stage("coverage") {
            sh("""
                set -eu
                report="${'$'}LAB_DIR/project/target/site/jacoco/jacoco.xml"
                line=$(grep -o '<counter type="LINE" missed="[0-9]*" covered="[0-9]*"/>' "${'$'}report" | tail -1)
                if [ -z "${'$'}line" ]; then
                  echo "the report of the coverage holds no line" >&2
                  exit 1
                fi
                missed=$(echo "${'$'}line" | sed -E 's/.*missed="([0-9]+)".*/\1/')
                covered=$(echo "${'$'}line" | sed -E 's/.*covered="([0-9]+)".*/\1/')
                total=${'$'}(( missed + covered ))
                if [ "${'$'}total" = "0" ]; then
                  echo "the report of the coverage holds no line" >&2
                  exit 1
                fi
                percent=${'$'}((( covered * 100 ) / total ))
                echo coverage=${'$'}percent threshold=${'$'}COVERAGE_MIN
                if [ "${'$'}percent" -lt "${'$'}COVERAGE_MIN" ]; then
                  echo "the coverage is under the floor the template declares" >&2
                  exit 1
                fi
            """.trimIndent())
        }
        stage("artifacts") {
            sh("""
                set -eu
                mkdir -p artifacts
                cp "${'$'}LAB_DIR/project/target/site/jacoco/jacoco.xml" artifacts/jacoco.xml
                cp "${'$'}LAB_DIR/project/target/bom.json" artifacts/bom.json
                for suite in unit integration containers; do
                  mkdir -p "artifacts/surefire-reports/${'$'}suite"
                  cp "${'$'}LAB_DIR/reports/${'$'}suite/"*.xml "artifacts/surefire-reports/${'$'}suite/"
                done
                echo artifact-jacoco=${'$'}(sha256sum artifacts/jacoco.xml | cut -d' ' -f1)
                echo artifact-sbom=${'$'}(sha256sum artifacts/bom.json | cut -d' ' -f1)
            """.trimIndent())
            archiveArtifacts("artifacts/**")
        }
        stage("publish") {
            sh("""
                set -eu
                publish="${'$'}LAB_DIR/publish"
                rm -rf "${'$'}publish"
                mkdir -p "${'$'}publish/app"
                cp "${'$'}LAB_DIR/project/target/"*.jar "${'$'}publish/app/app.jar"
                tar -cf "${'$'}publish/layer.tar" -C "${'$'}publish" app
                crane append --insecure -b "${'$'}BASE_IMAGE" -f "${'$'}publish/layer.tar" -t "${'$'}IMAGE_REGISTRY/${'$'}IMAGE_REPOSITORY:${'$'}IMAGE_TAG"
                echo published-digest=${'$'}(crane digest --insecure "${'$'}IMAGE_REGISTRY/${'$'}IMAGE_REPOSITORY:${'$'}IMAGE_TAG")
            """.trimIndent())
        }
        stage("manifests") {
            sh("""
                set -eu
                DIGEST=${'$'}(crane digest --insecure "${'$'}IMAGE_REGISTRY/${'$'}IMAGE_REPOSITORY:${'$'}IMAGE_TAG")
                cat > "${'$'}LAB_DIR/deployment.json" <<MANIFEST
                {
                  "apiVersion": "apps/v1",
                  "kind": "Deployment",
                  "metadata": {
                    "name": "staging",
                    "namespace": "${'$'}STAGING_NAMESPACE"
                  },
                  "spec": {
                    "replicas": 1,
                    "selector": {
                      "matchLabels": {
                        "app": "staging"
                      }
                    },
                    "template": {
                      "metadata": {
                        "labels": {
                          "app": "staging"
                        }
                      },
                      "spec": {
                        "automountServiceAccountToken": false,
                        "securityContext": {
                          "runAsNonRoot": true,
                          "runAsUser": 10001,
                          "runAsGroup": 10001,
                          "seccompProfile": {
                            "type": "RuntimeDefault"
                          }
                        },
                        "containers": [
                          {
                            "name": "staging",
                            "image": "${'$'}IMAGE_PULL_REGISTRY/${'$'}IMAGE_REPOSITORY@${'$'}DIGEST",
                            "command": ["/opt/java/openjdk/bin/java", "-XX:MaxRAMPercentage=60.0", "-jar", "/app/app.jar"],
                            "ports": [
                              {
                                "containerPort": 8080
                              }
                            ],
                            "resources": {
                              "requests": {
                                "memory": "384Mi"
                              },
                              "limits": {
                                "memory": "768Mi"
                              }
                            },
                            "securityContext": {
                              "allowPrivilegeEscalation": false,
                              "readOnlyRootFilesystem": true,
                              "capabilities": {
                                "drop": ["ALL"]
                              }
                            },
                            "volumeMounts": [
                              {
                                "name": "tmp",
                                "mountPath": "/tmp"
                              }
                            ]
                          }
                        ],
                        "volumes": [
                          {
                            "name": "tmp",
                            "emptyDir": {}
                          }
                        ]
                      }
                    }
                  }
                }
                MANIFEST
                cat > "${'$'}LAB_DIR/service.json" <<MANIFEST
                {
                  "apiVersion": "v1",
                  "kind": "Service",
                  "metadata": {
                    "name": "staging",
                    "namespace": "${'$'}STAGING_NAMESPACE"
                  },
                  "spec": {
                    "selector": {
                      "app": "staging"
                    },
                    "ports": [
                      {
                        "port": 8080,
                        "targetPort": 8080
                      }
                    ]
                  }
                }
                MANIFEST
            """.trimIndent())
        }
        stage("deliver") {
            withCredentials(dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file("staging-kubeconfig", "STAGING_KUBECONFIG")) {
                sh("set -eu; export KUBECONFIG=\$STAGING_KUBECONFIG; kubectl apply -f \"\$LAB_DIR/deployment.json\" -f \"\$LAB_DIR/service.json\"; kubectl rollout status deployment/staging --timeout=300s")
                sh("set -eu; export KUBECONFIG=\$STAGING_KUBECONFIG; echo ran-image=$(kubectl get pod -l app=staging -o jsonpath='{.items[0].status.containerStatuses[0].imageID}')")
                sh("set -eu; echo health=$(wget -qO- \"http://\$STAGING_SERVICE.\$STAGING_NAMESPACE.svc.cluster.local:\$STAGING_PORT/actuator/health\")")
                sh("set -eu; echo vets=$(wget -qO- \"http://\$STAGING_SERVICE.\$STAGING_NAMESPACE.svc.cluster.local:\$STAGING_PORT/vets\")")
                sh("set -eu; export KUBECONFIG=\$STAGING_KUBECONFIG; kubectl delete deployment staging --ignore-not-found --wait=true; kubectl delete service staging --ignore-not-found")
            }
        }
    }
}
