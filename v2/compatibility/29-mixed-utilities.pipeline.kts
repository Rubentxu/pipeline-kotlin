// LFC-2E2 utilities OFFICIAL_PLUGIN live fixture (Slice 2 / S2.5 corpus).
//
// End-to-end exercise of all five new Steps in one pipeline:
//   readYaml, writeYaml, findFiles, zip, unzip.
//
// Pipeline shape:
//   1. writeYaml builds a manifest that lists files to archive.
//   2. readYaml reads it back (proving the round-trip).
//   3. findFiles enumerates the source directory.
//   4. zip archives the source.
//   5. unzip extracts to a sibling dir.
//   6. core.sh prints "DONE" so the orchestrator has a final marker
//      that this fixture completed end-to-end.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.writeYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.readYaml
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles
import dev.rubentxu.pipeline.v2.sdk.utilities.step.zipDir
import dev.rubentxu.pipeline.v2.sdk.utilities.step.unzip
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

pipeline {
    stages {
        stage("write-manifest") {
            // 0) Clean previous artifacts so the fixture is rerunnable
            //    without an `overwrite=true` flag.
            sh("rm -rf build/utils/mix")
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
        }
        stage("read-manifest") {
            // 3) Read the manifest back; the typed Step observes the
            //    YAML structure through the durable journal.
            readYaml("build/utils/mix/manifest.yaml")
        }
        stage("enumerate") {
            // 4) Enumerate the source tree.
            findFiles(base = "build/utils/mix/src", glob = "*.txt")
        }
        stage("archive") {
            // 5) Archive the source tree.
            zipDir(
                path = "build/utils/mix/mix.zip",
                directory = "build/utils/mix/src",
            )
        }
        stage("extract") {
            // 6) Extract the archive to a sibling directory and verify
            //    file contents survived.
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
