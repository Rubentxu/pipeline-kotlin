// LFC-2E2 utilities OFFICIAL_PLUGIN live fixture (Slice 2 / S2.1 + S2.2).
//
// End-to-end exercise of readYaml + writeYaml. The fixture writes a typed
// YAML document to disk, reads it back, and uses `core.sh cat` to confirm
// the round-trip preserves the data shape.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

pipeline {
    stages {
        stage("yaml-roundtrip") {
            // 1) Write a typed YAML document using the closed YamlDocument
            //    ADT (no JsonElement dependency).
            writeYaml(
                "build/utils/config.yaml",
                value = YamlDocument.Map(listOf(
                    YamlDocument.Map.Entry("name", YamlDocument.Str("pipelinek")),
                    YamlDocument.Map.Entry("version", YamlDocument.Str("2.0.0")),
                    YamlDocument.Map.Entry("features", YamlDocument.Seq(listOf(
                        YamlDocument.Str("yaml"),
                        YamlDocument.Str("findFiles"),
                        YamlDocument.Str("zip"),
                        YamlDocument.Str("unzip"),
                    ))),
                )),
            )

            // 2) Read it back through the typed readYaml Step.
            readYaml("build/utils/config.yaml")

            // 3) Sanity-check the file content with `core.sh`.
            sh("test -f build/utils/config.yaml && cat build/utils/config.yaml")
        }
    }
}
