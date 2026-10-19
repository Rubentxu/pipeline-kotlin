pipeline {
    stages {
        stage("envtest") {
            // The check has to live INSIDE this script block. The `export` is scoped to
            // the shell that ran it, so a following `sh` step would start from a
            // fresh environment and observe nothing -- an assertion written as
            // a second step would pass on an empty variable while reading as a
            // proof that `isScriptBlock` had executed its body.
            // `${'$'}` because this is a Kotlin raw string: a bare `$MY_VAR` is
            // a Kotlin template reference and the scenario fails to COMPILE
            // rather than failing for the reason it is about.
            sh("""export MY_VAR=test_value && echo 'MY_VAR is set' && test "${'$'}MY_VAR" = test_value""", isScriptBlock = true, returnStdout = false)
        }
    }
}
