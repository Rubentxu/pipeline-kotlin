// The subject is what a FAILED branch leaves behind: the side effect it
// produced before failing stays, and the failure is named.
//
// Two facts, and the second one is the reason for the first. The engine
// does not roll the workspace back when a step fails, so a failed canary
// leaves artifacts in the workspace. That is worth knowing, because the
// receipt lists the artifacts and a reader who assumed "failed means
// nothing was written" would read the list wrong.
//
// The failing branch writes its marker BEFORE it fails, so the artifact
// is deterministic: it does not depend on which branch the engine happens
// to schedule first, which is what a parallel block makes unknowable.
pipeline {
    stages {
        stage("branches") {
            parallel {
                branch("left") {
                    sh("mkdir -p /workspace/out && printf 'left\\n' > /workspace/out/canary-branch-left.txt")
                }
                branch("right") {
                    sh("mkdir -p /workspace/out && printf 'right\\n' > /workspace/out/canary-branch-right.txt && exit 3")
                }
            }
        }
    }
}
