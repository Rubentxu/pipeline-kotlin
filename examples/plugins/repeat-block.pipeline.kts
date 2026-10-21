//@ expect-stdout iteration
import example.block.repeatBlock
pipeline {
    stages {
        stage("Repeat") {
            // The repeat COUNT is this scenario's whole subject: `repeatBlock(2)`
            // promises the body runs twice. Appending to a file and counting the
            // lines is what observes it -- a bare `echo` would stay green for a
            // block that ran once, or not at all, which is the same defect the
            // step exists to rule out.
            repeatBlock(2) {
                sh("echo iteration >> iterations.txt")
            }
            sh("test \"$(wc -l < iterations.txt)\" -eq 2")
        }
    }
}
