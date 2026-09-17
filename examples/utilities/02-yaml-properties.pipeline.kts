// examples/utilities/02-yaml-properties.pipeline.kts
//
// XCA-1C.1 — canonical product fixture for the YAML and properties READ surfaces.
//
// Exercises, through the installed CLI and the registry seam:
//   1. readYaml       — decode a YAML file       (capability utilities.yaml.operations)
//   2. readProperties — decode a properties file (capability utilities.properties.operations)
//
// SCOPE NOTE (deliberate, not an omission): `writeYaml` and `writeProperties` are NOT
// exercised here. Their DSL facades expose raw kotlinx types
//
//     fun StageScope.writeYaml(path: String, value: JsonElement)
//     fun StageScope.writeProperties(path: String, value: JsonObject)
//
// while `writeJSON` — the only value-taking facade proven to work from a script — takes a
// String and parses internally. No `.pipeline.kts` in this repository imports
// kotlinx.serialization, and pipeline-scripting-api declares no serialization dependency.
// Whether a script author can construct JsonElement/JsonObject is therefore UNPROVEN.
//
// Crediting writeYaml/writeProperties to this file would repeat exactly the false-claim
// pattern XCA-1A removed, so the ledger does not do so. See
// docs/v2/07-uat/XCA1C_UTILITIES_SCENARIO_RECON.md (blocker, options A/B).

import pipeline.utilities.yaml.readYaml
import pipeline.utilities.properties.readProperties

pipeline {
    stages {
        stage("yamlAndProperties") {
            readYaml(path = "examples/utilities/02-input.yaml")
            readProperties(path = "examples/utilities/02-input.properties")
        }
    }
}
