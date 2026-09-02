// Compatibility fixture 10: smoke end-to-end (checkout → build+archive).
// LF-0208 note: under the Single Runtime Spine each stage gets its own
// workspace, so archiveArtifacts lives in the SAME stage as the build step
// (Jenkins-faithful workspace continuity).
pipeline {
    stages {
        stage("checkout") {
            sh("cd /tmp && rm -rf smoke-repo && git init -q smoke-repo && cd smoke-repo && echo smoke > README.md && git add . && git -c user.email=s@l -c user.name=s commit -qm init")
            sh("git clone /tmp/smoke-repo .")
        }
        stage("build") {
            sh("mkdir -p build/libs && echo jar > build/libs/smoke.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
