// LFC-2E2 utilities OFFICIAL_PLUGIN live fixture (Slice 2 / S2.3).
//
// End-to-end exercise of findFiles. The fixture creates a directory
// structure, then enumerates it through the typed Step with two glob
// variants (direct children and recursive).
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles

pipeline {
    stages {
        stage("find-files") {
            // 1) Set up a small directory tree (writeYaml was used
            //    here so the typed Steps stay inside the OFFICIAL_PLUGIN
            //    surface; `core.sh` would also work but findFiles is
            //    what this fixture is about).
            sh("""
                mkdir -p build/utils/scan/sub
                echo 'one' > build/utils/scan/a.txt
                echo 'two' > build/utils/scan/b.dat
                echo 'three' > build/utils/scan/sub/c.txt
                echo 'four' > build/utils/scan/sub/d.txt
            """.trimIndent())

            // 2) Direct-children glob (Jenkins default).
            findFiles(base = "build/utils/scan", glob = "*.txt")

            // 3) Recursive glob (Jenkins-compat two-stars-slash).
            findFiles(base = "build/utils/scan", glob = "**/*.txt")

            // 4) Cross-check with `core.sh find`.
            sh("find build/utils/scan -name '*.txt'")
        }
    }
}
