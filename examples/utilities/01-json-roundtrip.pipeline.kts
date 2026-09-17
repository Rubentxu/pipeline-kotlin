// examples/utilities/01-json-roundtrip.pipeline.kts
//
// First OFFICIAL_PLUGIN vertical slice (LFC-2E2 FASE 6).
//
// Exercises the `pipeline.utilities.json` plugin end-to-end through the registry
// seam: the DSL extensions `readJSON`, `writeJSON`, and `sha256` are thin facades
// over the generic `registryStep` primitive that lower to `StepSpec.RegistryStepSpec`.
// Each Step MUST resolve via the `StepDefinitionContributor` SPI the plugin JAR
// declares.
//
//   1. echo — sanity banner
//   2. readJSON — decode a JSON file (capability UTILITIES_JSON_CAPABILITY)
//   3. writeJSON — write a JSON value back to a new file
//   4. sha256 — compute SHA-256 of the new file's content (capability UTILITIES_SHA_CAPABILITY)
//
// Expected runtime: every Step goes through the registry path
// (`utilities.readJSON` resolves via the `StepDefinitionContributor` SPI that the
// plugin JAR declares). The host runtime supplies the capabilities before the
// handlers run.
//
// This file is the canonical real fixture for `pipeline.utilities.json@1.0.0`.
// It MUST pass `just corpus 01-json-roundtrip` AND the canonical
// `CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` test.

import pipeline.utilities.json.readJSON
import pipeline.utilities.json.writeJSON
import pipeline.utilities.json.sha256

pipeline {
    val jsonFile = "build/utilities-roundtrip.json"

    stages {
        stage("roundtrip") {
            steps {
                echo("Before readJSON")
                readJSON(path = "examples/utilities/01-input.json")
                echo("readJSON step dispatched")

                writeJSON(
                    path = jsonFile,
                    value = """{"pipeline":"kotlin","stage":"roundtrip","version":1}""",
                    prettyPrint = true,
                )
                echo("writeJSON step dispatched to ${'$'}jsonFile")

                sha256(path = jsonFile)
                echo("sha256 step dispatched for ${'$'}jsonFile")
            }
        }
    }
}
