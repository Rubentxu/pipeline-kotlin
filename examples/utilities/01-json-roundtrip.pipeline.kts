// examples/utilities/01-json-roundtrip.pipeline.kts
//
// First OFFICIAL_PLUGIN vertical slice (LFC-2E2 FASE 6, updated for U7.5).
//
// Exercises the `pipeline.utilities.json` plugin end-to-end through the registry
// seam: the DSL extensions `readJSON`, `writeJSON`, and `sha256` are thin facades
// over the generic `registryStep` primitive that lower to `StepSpec.RegistryStepSpec`.
// Each Step MUST resolve via the `StepDefinitionContributor` SPI the plugin JAR
// declares.
//
//   1. readJSON — decode a JSON file (capability UTILITIES_JSON_CAPABILITY)
//   2. writeJSON — write a JSON value back to a new file
//   3. sha256 — compute SHA-256 of the new file's content (capability UTILITIES_SHA_CAPABILITY)
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
    stages {
        stage("roundtrip") {
            readJSON(path = "examples/utilities/01-input.json")
            writeJSON(
                path = "build/utilities-roundtrip.json",
                value = """{"pipeline":"kotlin","stage":"roundtrip","version":1}""",
                prettyPrint = true,
            )
            sha256(path = "build/utilities-roundtrip.json")
        }
    }
}
