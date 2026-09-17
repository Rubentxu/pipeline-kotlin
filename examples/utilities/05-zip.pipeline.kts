// examples/utilities/05-zip.pipeline.kts
//
// XCA-1C.1 — canonical product fixture for the archive (zip) surfaces.
// JDK-bundled (java.util.zip), fail-closed against Zip Slip and absolute paths.
// Capability utilities.archive.operations.

import pipeline.utilities.archive.zip
import pipeline.utilities.archive.unzip

pipeline {
    stages {
        stage("zip") {
            zip(
                sourceDir = "examples/utilities",
                targetZip = "build/utilities.zip",
                overwrite = true,
            )
            unzip(
                sourceZip = "build/utilities.zip",
                targetDir = "build/utilities-unzip",
                overwrite = true,
            )
        }
    }
}
