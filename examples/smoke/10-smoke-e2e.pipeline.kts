// Compatibility fixture 10: smoke end-to-end (checkout → build+archive).
// Note: under the Single Runtime Spine each stage gets its own
// workspace, so archiveArtifacts lives in the SAME stage as the build step
// (Jenkins-faithful workspace continuity).
// The fixture repository used to be created at /tmp/smoke-repo and cloned from
// that absolute path. Two rules were broken at once: AGENTS.md section 3 gives
// every example a private temporary directory and forbids shared marker paths,
// so two concurrent runs fought over one directory; and the `rm -rf` aimed at a
// shared location would have deleted whatever else was sitting there. Both are
// gone with a relative path -- `repo` resolves inside this stage's own
// workspace, so the clone exercises the same git operation without leaving the
// authorised root.
//
// The fixture commits the way a governed machine expects a commit to be made:
// a Conventional Commit message, and the identity the machine itself declares.
// Both are read from the git configuration instead of being invented here,
// because inventing them means writing somebody's identity into a tracked
// file, and because a commit that the surrounding policy refuses would fail
// the scenario for a reason that has nothing to do with the product. The
// identity is set in the fixture's own repository so that the git tooling
// sees an explicit identity for this repository, which is what it needs when
// the repository has no remote of its own.
// The guarantee checked below is unaffected by any of this: the clone has to
// carry the committed file, and a fixture that stops committing stops
// passing.
pipeline {
    stages {
        stage("checkout") {
            sh("rm -rf repo && mkdir -p repo && cd repo && git init -q . && git config user.email \"$(git config --global user.email)\" && git config user.name \"$(git config --global user.name)\" && echo smoke > README.md && git add . && git commit -qm \"chore(fixture): el contenido que el escenario clona\"")
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
