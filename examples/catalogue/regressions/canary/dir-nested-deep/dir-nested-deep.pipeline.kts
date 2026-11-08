// The subject is the DEPTH of the working-directory contract: five levels
// of `dir()`, the file written at the deepest one, and the working
// directory RESTORED afterwards.
//
// The battery had a three-level case and a one-level restore case. A
// contract that holds three levels down and breaks on the fifth is a
// contract that was never tested at the fifth, and the
// depth is where a path is joined and joined again.
//
// The restore is checked by a step in the SAME stage but OUTSIDE the
// `dir()` blocks: inside a block the working directory is the one that
// block names, so asking there would ask the wrong question. The check
// is `pwd | grep -qx /workspace`, about the directory the step really
// ran in, not about a marker file that a wrong directory could still
// have written.
pipeline {
    stages {
        stage("nested") {
            dir("a/b/c/d/e") {
                sh("printf 'CANARY-DEEP\\n' > payload.txt")
            }
            dir("a/b/c/d/e") {
                sh("cat payload.txt | grep -qx CANARY-DEEP")
            }
            sh("pwd | grep -qx /workspace && mkdir -p /workspace/out && printf 'restored\\n' > /workspace/out/canary-dir-restore.txt")
        }
    }
}
