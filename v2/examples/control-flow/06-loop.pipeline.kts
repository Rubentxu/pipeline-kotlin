pipeline {
    stages {
        stage("loop") {
            // The loop count is the subject. Redirecting to a file and counting
            // the lines is what turns "the shell started" into "it iterated
            // three times" -- the bare echo below would stay green for a loop
            // body that ran once, or not at all.
            sh("""for i in 1 2 3; do echo "iteration ${'$'}i"; done > iterations.txt""", isScriptBlock = true, returnStdout = false)
            sh("""test "${'$'}(wc -l < iterations.txt)" -eq 3""")
        }
    }
}
