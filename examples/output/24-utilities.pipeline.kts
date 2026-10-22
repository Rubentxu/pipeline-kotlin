// The utilities plugin, end to end.
//
// The typed codec steps, the tree enumeration and the archive round trip are
// exercised in ONE stage, because that is where they belong: every hand-off
// goes through the workspace filesystem, and each stage resolves to its own
// directory, so a producer in one stage and its consumer in another would be
// reading two different trees.
//
// The guarantees, in the order they are earned:
//
//   - a JSON document written by the product reads back through the product;
//   - a YAML document written by the product reads back through the product;
//   - the documents are on disk with the content they were given, not merely
//     with the right name;
//   - the tree is enumerated;
//   - the archive extracts to a different directory;
//   - and every extracted entry has the BYTES it had before the round trip.
//
// The last one is the one worth reading twice. A presence check is satisfied
// by an archive that wrote the right names and the wrong content, which is
// exactly the failure a round trip can have and the eye cannot see. The
// entries are compared byte for byte, so the proof is about the bytes.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readJson
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeJsonRaw
import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.step.zipDir
import dev.rubentxu.pipeline.v2.sdk.utilities.step.unzip
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

pipeline {
    stages {
        stage("utilities") {
            // The source tree the archive is made of.
            sh("""
                rm -rf build/utilities
                mkdir -p build/utilities/src/nested
                echo 'one' > build/utilities/src/a.txt
                echo 'two' > build/utilities/src/b.txt
                echo 'three' > build/utilities/src/nested/c.txt
            """.trimIndent())

            // A JSON document, written and read back by the product. The step's
            // typed output is observed through the durable journal rather than
            // bound to a value: steps are side effects in this DSL.
            writeJsonRaw("build/utilities/data.json", """{"name":"alice","age":30}""")
            readJson("build/utilities/data.json")
            sha256("build/utilities/data.json")

            // A YAML document, written from the closed document type and read
            // back by the product.
            writeYaml(
                "build/utilities/config.yaml",
                value = YamlDocument.Map(listOf(
                    YamlDocument.Map.Entry("name", YamlDocument.Str("pipelinek")),
                    YamlDocument.Map.Entry("version", YamlDocument.Str("2.0.0")),
                    YamlDocument.Map.Entry("features", YamlDocument.Seq(listOf(
                        YamlDocument.Str("yaml"),
                        YamlDocument.Str("json"),
                        YamlDocument.Str("findFiles"),
                        YamlDocument.Str("zip"),
                        YamlDocument.Str("unzip"),
                    ))),
                )),
            )
            readYaml("build/utilities/config.yaml")

            // The tree is enumerated, archived and extracted to a sibling
            // directory.
            findFiles(base = "build/utilities/src", glob = "*.txt")
            zipDir(
                path = "build/utilities/source.zip",
                directory = "build/utilities/src",
            )
            unzip(
                path = "build/utilities/source.zip",
                destination = "build/utilities/out",
            )

            // The documents landed with the content they were given.
            sh("test -f build/utilities/data.json && grep -q alice build/utilities/data.json")
            sh("test -f build/utilities/config.yaml && grep -q pipelinek build/utilities/config.yaml")

            // The entries survived the round trip with their bytes, not merely
            // with their names.
            sh("""
                test -f build/utilities/out/a.txt
                test -f build/utilities/out/b.txt
                test -f build/utilities/out/nested/c.txt
                cmp build/utilities/src/a.txt build/utilities/out/a.txt
                cmp build/utilities/src/b.txt build/utilities/out/b.txt
                cmp build/utilities/src/nested/c.txt build/utilities/out/nested/c.txt
            """.trimIndent())
        }
    }
}
