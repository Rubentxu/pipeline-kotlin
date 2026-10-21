//@ expect-stdout alpha
// End-to-end exercise of the utilities Steps as one chain: writeJsonRaw,
// readJson, writeYaml, readYaml, findFiles, zipDir, unzip and sha256.
//
// The whole chain lives in ONE stage, and that is a correction rather than a
// simplification. Every hand-off in this scenario goes through the workspace
// filesystem: the manifest written by `writeYaml` is read back by `readYaml`,
// the source tree is enumerated by `findFiles`, archived by `zipDir`, and the
// archive is expanded by `unzip`. Per the `WorkspaceResolver`, each stage
// resolves to its own directory, `<controlRoot>/workspace/<stage>-<index>/`,
// so a producer in one stage and its consumer in another were reading two
// different trees. The stage boundary was never the subject here; the
// utilities round trip was.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJsonRaw
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.step.zipDir
import dev.rubentxu.pipeline.v2.sdk.utilities.step.unzip
import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

pipeline {
    stages {
        stage("mixed-utilities") {
            // 1) Create the source tree.
            sh("""
                mkdir -p build/utils/mix/src
                echo 'alpha' > build/utils/mix/src/a.txt
                echo 'beta' > build/utils/mix/src/b.txt
            """.trimIndent())

            // 2) Write a manifest as YAML using the typed YamlDocument ADT.
            writeYaml(
                "build/utils/mix/manifest.yaml",
                value = YamlDocument.Map(listOf(
                    YamlDocument.Map.Entry("name", YamlDocument.Str("mix")),
                    YamlDocument.Map.Entry("files", YamlDocument.Seq(listOf(
                        YamlDocument.Str("a.txt"),
                        YamlDocument.Str("b.txt"),
                    ))),
                )),
            )

            // 3) Read the manifest back; the typed Step observes the
            //    YAML structure through the durable journal.
            readYaml("build/utils/mix/manifest.yaml")

            // 4) Write and read back a JSON document, so both structured
            //    formats travel the same round trip.
            writeJsonRaw("build/utils/mix/data.json", """{"name":"alice","age":30}""")
            readJson("build/utils/mix/data.json")

            // 5) Enumerate the source tree.
            findFiles(base = "build/utils/mix/src", glob = "*.txt")

            // 6) Archive the source tree and hash what went into it.
            zipDir(
                path = "build/utils/mix/mix.zip",
                directory = "build/utils/mix/src",
            )
            sha256("build/utils/mix/src/a.txt")
            sha256("build/utils/mix/src/b.txt")

            // 7) Extract the archive to a sibling directory and verify
            //    the file contents survived the round trip.
            unzip(
                path = "build/utils/mix/mix.zip",
                destination = "build/utils/mix/out",
            )
            sh("""
                test -f build/utils/mix/out/a.txt
                test -f build/utils/mix/out/b.txt
                cat build/utils/mix/out/a.txt
                cat build/utils/mix/out/b.txt
            """.trimIndent())
        }
    }
}
