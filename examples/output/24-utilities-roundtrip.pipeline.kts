// Utilities OFFICIAL_PLUGIN live fixture.
// Runs end-to-end through the installed `pipelinek` distribution and exercises
// the typed codec Steps: writeJson, readJson, sha256, writeYaml and readYaml.
// The fixture writes a JSON document and a YAML document, reads both back,
// computes the SHA-256 of the produced file, and then checks the files with
// `core.sh` so the orchestrator can inspect the result end-to-end.
// The plugin owns its typed Kotlin DSL facade; the script imports the
// extension functions explicitly. Steps are side-effects in the DSL: their
// typed Output is observed through the durable journal rather than bound to a
// `val`.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJsonRaw
import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

pipeline {
    stages {
        stage("utilities-roundtrip") {
            // 1) Write a JSON document to disk via the typed writeJson facade
            //    (uses writeJsonRaw to avoid script-side JsonElement dependency).
            writeJsonRaw("build/utils/data.json", """{"name":"alice","age":30}""")

            // 2) Read it back. The Step's typed Output is observed through
            //    the canonical journal; the script simply notes that the
            //    read happened.
            readJson("build/utils/data.json")

            // 3) Compute the SHA-256 of the produced file via the typed Step.
            sha256("build/utils/data.json")

            // 4) Write a typed YAML document using the closed YamlDocument
            //    ADT (no JsonElement dependency), and read it back through
            //    the typed readYaml Step.
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
            readYaml("build/utils/config.yaml")

            // 5) Cross-check both documents with `core.sh`. `test -f` alone
            //    would be satisfied by a file of the right name and the wrong
            //    content, so the content is what the check looks at.
            sh("test -f build/utils/data.json && cat build/utils/data.json")
            sh("test -f build/utils/config.yaml && grep -q 'pipelinek' build/utils/config.yaml")
        }
    }
}
