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
                // The body prints its marker AND appends it, so the run's output shows the body
                // ran and the file shows how many times. Before, the body only appended, and the
                // `expect-stdout iteration` directive this scenario carried had been dead since
                // the commit that introduced the file: the text it waited for never reached the
                // output, so the scenario could not have failed for the reason it declared.
                sh("echo iteration | tee -a iterations.txt")
            }
            sh("test \"$(wc -l < iterations.txt)\" -eq 2")
        }
    }
}
