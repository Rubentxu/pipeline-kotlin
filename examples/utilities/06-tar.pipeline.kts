// examples/utilities/06-tar.pipeline.kts
//
// XCA-1C.1 — canonical product fixture for the archive (tar) surfaces.
// U7 spike decision: pure JDK implementation, reusing the SAME
// utilities.archive.operations capability port as zip/unzip (no new token).

import pipeline.utilities.archive.tarCreate
import pipeline.utilities.archive.tarExtract

pipeline {
    stages {
        stage("tar") {
            tarCreate(
                sourceDir = "examples/utilities",
                targetTar = "build/utilities.tar",
                overwrite = true,
            )
            tarExtract(
                sourceTar = "build/utilities.tar",
                targetDir = "build/utilities-untar",
                overwrite = true,
            )
        }
    }
}
