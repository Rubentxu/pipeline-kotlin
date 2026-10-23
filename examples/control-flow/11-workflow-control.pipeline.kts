//@ expect-stdout step-A
//@ expect-outcome unstable

// THE GUARANTEE UNDER TRIAL
//
// Three workflow controls, each observed through what it left behind.
//
//  - `dir` MOVES the working directory. The `pwd` the stage records has to
//    name that directory, because "the shell started" is not "the path
//    changed".
//  - `deleteDir` REMOVES what was written under it. A destructive step's
//    guarantee is an ABSENCE, so the absence is the witness: the files the
//    stage wrote must not be there afterwards.
//  - `catchError` CATCHES a failure, and the run then ends well AND is not a
//    success: it concludes UNSTABLE. The exit code cannot say that, because a
//    caught failure and a clean run both end the process well. The conclusion
//    the run PUBLISHED is what the harness compares, and the caught failure is
//    the only thing that makes this run unstable.
pipeline {
    stages {
        stage("workspace-control") {
            // A relative path, resolved inside this example's own private workspace. It used to
            // be `/tmp/workflow-test`, which was outside the authorised root: the working-directory
            // projection exempted absolute paths from containment, so the pipeline passed and the
            // exemption is now gone. A shared /tmp path was also a second, separate violation --
            // AGENTS.md section 3 gives every example a private workspace and forbids shared
            // marker paths -- so this fixes both at once instead of reintroducing an escape
            // through the relative form.
            dir("workflow-test") {
                sh("echo 'inside dir'")
                // `dir` exists to move the working directory, and "it ran" is
                // not the same property as "the path changed". Recording `pwd`
                // into a file and grepping it proves the projection moved,
                // where the bare echo above only proved the shell started.
                sh("pwd > where.txt && test -s where.txt && grep -q 'workflow-test' where.txt")
                sh("touch marker")
            }
            deleteDir()
            // `deleteDir` is a destructive step, and its guarantee is an
            // ABSENCE. The marker written above is the witness: if the step
            // resolved to a no-op the file would survive and this scenario
            // would still have been green, which is the failure mode a
            // destructive step cannot afford.
            // The witness path carries the `workflow-test/` prefix on purpose.
            // `touch marker` ran INSIDE `dir`, so the file it created is
            // `workflow-test/marker`; checking bare `marker` at the stage root
            // would ask about a path that was never created there and would
            // pass unconditionally. That version was written first and was
            // vacuous -- it could not fail.
            sh("test ! -e workflow-test/marker && test ! -e workflow-test/where.txt")
        }
        stage("retry-block") {
            timeout(30, "SECONDS") {
                retry(3) {
                    sh("echo 'step-A'")
                    sh("echo 'step-B'")
                }
            }
        }
        stage("error-handling") {
            catchError {
                sh("exit 1")
            }
        }
    }
}
