// WU-RP-043 N2(c) — intentional-failure fixture for the CI dogfood job.
//
// This script MUST fail: `sh` runs a command that exits 1, so the pipeline
// finishes with FAILURE and pipelinek exits non-zero. The CI job asserts that
// exact non-zero exit so an intentional failure turns CI red (criterion d).
// It exercises the same runner as the success script (01-basic) — only the
// outcome differs — proving that a red CI means the dogfood runner really
// executed, not that it silently passed.
pipeline {
    stages {
        stage("intentional-failure") {
            sh("echo 'WU-RP-043: intentional failure for CI red-path check' && exit 1")
        }
    }
}
