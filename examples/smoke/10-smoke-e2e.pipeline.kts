// Compatibility fixture 10: smoke end-to-end (checkout → build+archive).
// LF-0208 note: under the Single Runtime Spine each stage gets its own
// workspace, so archiveArtifacts lives in the SAME stage as the build step
// (Jenkins-faithful workspace continuity).
//
// The fixture repository is created inside PIPELINEK_E2E_HOME, the launcher's
// private directory for this scenario. A fixed /tmp/smoke-repo is shared with
// every other run on the machine, and two concurrent runs would race on it:
// one deleting the repository the other is cloning.
// PIPELINEK_E2E_HOME is the launcher's private directory for this scenario.
// Run by hand, the working directory is the fallback: still not a shared /tmp
// path, and the launcher is what makes it unique per scenario.
val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")
val fixture = "$home/smoke-repo"

pipeline {
    stages {
        stage("checkout") {
            sh("rm -rf '$fixture' && git init -q '$fixture' && cd '$fixture' && echo smoke > README.md && git add . && git -c user.email=s@l -c user.name=s commit -qm init")
            sh("git clone '$fixture' .")
        }
        stage("build") {
            sh("mkdir -p build/libs && echo jar > build/libs/smoke.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
