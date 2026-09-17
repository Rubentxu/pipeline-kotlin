// examples/utilities/03-filesystem.pipeline.kts
//
// XCA-1C.1 — canonical product fixture for the filesystem traversal surfaces.
//   1. findFiles — LIST-shaped output (capability utilities.filesystem.operations)
//   2. touch     — timestamp effect
// Both are reached through the registry seam as `StepSpec.RegistryStepSpec`.

import pipeline.utilities.filesystem.findFiles
import pipeline.utilities.filesystem.touch

pipeline {
    stages {
        stage("filesystem") {
            findFiles(root = "examples/utilities", glob = "*.json", maxDepth = 4)
            touch(path = "build/utilities-touch.txt", createDirs = true)
        }
    }
}
