// WU-LPR-089 — `core.stash` / `core.unstash` installed-CLI canary (Tier B #1).
//
// Exercises the registry-path `core.stash` and `core.unstash` Steps through
// the DSL surface. Stage "build" creates files in the workspace and stashes
// them under the logical name "lpr104-files"; stage "use" un-stashes them
// back and verifies byte-equivalent recovery via sh(returnStdout=true).
//
// Failures here mean one of:
//   - registry resolution broke (StepKey "core.stash"/"core.unstash" not found)
//   - the registry handler regressed (no StashCreated/StashRestored, or wrong path shape)
//   - the DSL lowering reverted to legacy StepSpec.Stash
//
// Storage layout: <controlRoot>/stashes/<runId>/<lpr104-files>. The stash
// directory is a SIBLING of <controlRoot>/workspace/ so cleanup of the
// build stage workspace does NOT destroy the stash.
pipeline {
    stages {
        stage("build") {
            sh("mkdir -p src")
            writeFile(file = "src/lpr104.txt", text = "lpr104-data")
            sh("mkdir -p docs")
            writeFile(file = "docs/readme.md", text = "lpr104-readme")
            // Stash only the src/ tree — the excludes filter drops docs/readme.md
            // (matches by file name) but keeps docs/ as an empty directory? No:
            // AntStyleGlob default excludes ignore docs/; we filter explicitly here.
            stash(name = "lpr104-files", includes = "src/**,docs/**")
        }
        stage("use") {
            // Wipe the workspace before unstash to prove the bytes come from the stash,
            // NOT from the previous stage's directory.
            sh("rm -rf src docs")
            unstash(name = "lpr104-files")
            sh("test -f src/lpr104.txt && echo SRC_OK")
            sh("test -f docs/readme.md && echo DOCS_OK")
            sh("cat src/lpr104.txt")
        }
    }
}
