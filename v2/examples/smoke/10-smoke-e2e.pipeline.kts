// Compatibility fixture 10: smoke end-to-end (checkout → build+archive).
// LF-0208 note: under the Single Runtime Spine each stage gets its own
// workspace, so archiveArtifacts lives in the SAME stage as the build step
// (Jenkins-faithful workspace continuity).
//
// The fixture repository used to be created at /tmp/smoke-repo and cloned from
// that absolute path. Two rules were broken at once: AGENTS.md section 3 gives
// every example a private temporary directory and forbids shared marker paths,
// so two concurrent runs fought over one directory; and the `rm -rf` aimed at a
// shared location would have deleted whatever else was sitting there. Both are
// gone with a relative path -- `repo` resolves inside this stage's own
// workspace, so the clone exercises the same git operation without leaving the
// authorised root.
pipeline {
    stages {
        stage("checkout") {
            sh("rm -rf repo && mkdir -p repo && cd repo && git init -q . && echo smoke > README.md && git add . && git -c user.email=s@l -c user.name=s commit -qm init")
            sh("git clone repo checkout")
            // `git clone` exits 0 for an empty source repo and for a partial
            // one, so the clone's exit code says the command ran, not that
            // the commit travelled. This is the cross-step carrier of the
            // whole smoke chain -- stage 2 archives what stage 1 cloned --
            // so a clone that silently produced nothing would leave every
            // later stage green on an empty tree.
            sh("test -s checkout/README.md && test \"$(cat checkout/README.md)\" = smoke")
        }
        stage("build") {
            sh("mkdir -p build/libs && echo jar > build/libs/smoke.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
