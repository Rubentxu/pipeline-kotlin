// examples/testing/junit-success.pipeline.kts
//
// LFC-2E3-T4 — first REAL fixture for the `pipeline.testing` OFFICIAL_PLUGIN.
//
// Exercises `core.junit` end-to-end through the installed CLI and the plugin JAR:
//
//   pipeline-application \
//     --plugin-jar examples/testing-plugin/build/libs/testing-plugin-0.1.0-SNAPSHOT.jar \
//     examples/testing/junit-success.pipeline.kts
//
// The `junit(...)` extension is a thin typed facade over the generic
// `registryStep(...)` primitive: it lowers to `StepSpec.RegistryStepSpec`, the
// runtime resolves `core.junit` through the `StepDefinitionContributor` SPI the
// plugin JAR declares, and the handler reaches the filesystem ONLY through the
// declared `testing.filesystem.operations` capability.
//
// The report is fully green: 2 suites, 4 cases, 0 failures.
//
// Expected terminal outcome: SUCCESS. The Step's durable typed output carries
// the parsed TestReport (2 suites / 4 passed).

import pipeline.testing.junit.junit

pipeline {
    stages {
        stage("junitSuccess") {
            junit(reportPaths = listOf("examples/testing/junit-success.xml"))
        }
    }
}
