//@ expect-stdout x is positive
//@ expect-transcript x is positive

// Two scripted control flows in one scenario: a branch that takes one arm, and
// a loop whose body must run a known number of times.
// The branch is observable in what the pipeline PRODUCED: the other arm would
// print a different sentence, so a branch that took the wrong way is visible
// rather than merely a script that ran.
// The loop count is observed by counting the lines the body wrote. A bare
// echo per iteration would stay green for a body that ran once, or not at
// all, and a loop that ran three times when it should have run five is
// exactly the regression the count is there to catch.
pipeline {
    stages {
        stage("scripted-control-flow") {
            script {
                val x = 1
                if (x > 0) {
                    echo("x is positive")
                } else {
                    echo("x is not positive")
                }
            }
            sh("""for i in 1 2 3; do echo "iteration ${'$'}i"; done > iterations.txt""", isScriptBlock = true, returnStdout = false)
            sh("""test "${'$'}(wc -l < iterations.txt)" -eq 3""")
        }
    }
}
