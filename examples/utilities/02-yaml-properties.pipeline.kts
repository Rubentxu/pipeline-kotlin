// examples/utilities/02-yaml-properties.pipeline.kts
//
// XCA-1C.2 — canonical product fixture for the YAML and properties surfaces.
//
// Covers all four surfaces with a write -> read round-trip:
//   1. writeYaml       — serialized YAML document -> file   (utilities.yaml.operations)
//   2. readYaml        — file -> JsonElement
//   3. writeProperties — serialized .properties text -> file (utilities.properties.operations)
//   4. readProperties  — file -> JsonObject
//
// The String overloads are script-safe by construction: the String is the TARGET
// FORMAT'S OWN TEXT, parsed at the DSL facade into the same canonical typed input as
// the JsonElement/JsonObject overloads. Same StepKey, same StepDefinition, same
// handler, same capability. This is uniform with `writeJSON(path, value: String)`.
//
// The JsonElement/JsonObject overloads are retained: this change is strictly additive.

import pipeline.utilities.yaml.writeYaml
import pipeline.utilities.yaml.readYaml
import pipeline.utilities.properties.writeProperties
import pipeline.utilities.properties.readProperties

pipeline {
    stages {
        stage("yamlAndProperties") {
            writeYaml(
                file = "build/utilities/yaml-roundtrip.yaml",
                content = """
                    pipeline: kotlin
                    stage: yaml-roundtrip
                    version: 1
                """.trimIndent(),
            )
            readYaml(path = "build/utilities/yaml-roundtrip.yaml")

            writeProperties(
                file = "build/utilities/properties-roundtrip.properties",
                content = """
                    pipeline=kotlin
                    stage=properties-roundtrip
                    version=1
                """.trimIndent(),
            )
            readProperties(path = "build/utilities/properties-roundtrip.properties")
        }
    }
}
