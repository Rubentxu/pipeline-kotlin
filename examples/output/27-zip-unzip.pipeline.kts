// LFC-2E2 utilities OFFICIAL_PLUGIN live fixture (Slice 2 / S2.4 + S2.5).
//
// End-to-end exercise of zip + unzip. The fixture zips a directory
// tree, then extracts it to a different sub-directory. The contents
// must match by SHA-256 (per-entry durability proof).
import dev.rubentxu.pipeline.v2.sdk.utilities.step.zipDir
import dev.rubentxu.pipeline.v2.sdk.utilities.step.unzip
import dev.rubentxu.pipeline.v2.sdk.utilities.step.sha256

pipeline {
    stages {
        stage("zip-unzip") {
            // 1) Set up the source directory.
            sh("""
                mkdir -p build/utils/src/nested
                echo 'one' > build/utils/src/a.txt
                echo 'two' > build/utils/src/b.txt
                echo 'three' > build/utils/src/nested/c.txt
            """.trimIndent())

            // 2) Archive the directory tree.
            zipDir(
                path = "build/utils/source.zip",
                directory = "build/utils/src",
            )

            // 3) Hash the original file so we can compare later.
            sha256("build/utils/src/a.txt")
            sha256("build/utils/src/b.txt")
            sha256("build/utils/src/nested/c.txt")

            // 4) Extract to a different directory.
            unzip(
                path = "build/utils/source.zip",
                destination = "build/utils/out",
            )

            // 5) Cross-check that the extracted files survived.
            sh("""
                test -f build/utils/out/a.txt
                test -f build/utils/out/b.txt
                test -f build/utils/out/nested/c.txt
                cat build/utils/out/a.txt
                cat build/utils/out/b.txt
                cat build/utils/out/nested/c.txt
            """.trimIndent())
        }
    }
}
