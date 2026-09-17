// examples/utilities/04-checksums.pipeline.kts
//
// XCA-1C.1 — canonical product fixture for the checksum surfaces.
// Each algorithm is its own StepKey (a typed closed HashAlgorithm enum inside the
// plugin, NOT a string-keyed generic Step). Capability utilities.checksum.operations.

import pipeline.utilities.checksums.md5
import pipeline.utilities.checksums.sha1
import pipeline.utilities.checksums.sha512

pipeline {
    stages {
        stage("checksums") {
            md5(path = "examples/utilities/01-input.json")
            sha1(path = "examples/utilities/01-input.json")
            sha512(path = "examples/utilities/01-input.json")
        }
    }
}
