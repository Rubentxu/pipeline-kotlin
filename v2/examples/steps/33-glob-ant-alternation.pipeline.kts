// The Ant glob that says what it means.
//
// `core.stash` reads a comma-separated list of Ant patterns. Two defects used to meet here,
// and each one alone is enough to make this scenario fail:
//
//   1. THE LIST. The field was split on EVERY comma, so `src/**/*.{txt,md}` was shredded into
//      `src/**/*.{txt` and `md}`. Neither fragment matches anything, so the stash matched no
//      files and the step failed with "matched no files for pattern".
//
//   2. THE GLOB. The pattern reached an engine that speaks Spring's dialect, where a
//      colon-free `{...}` is a WILDCARD rather than an alternation. `src/**/*.{txt,md}` then
//      matched every file in the tree -- including the one this scenario exists to leave
//      behind. An operator who wrote "the text and markdown files" got the private key too.
//
// The assertion that matters is the NEGATIVE one: after the restore, the file that must not
// have been stashed is not there. Under the old behaviour it WAS there, and under the
// "fixed list, broken glob" half-fix it would still be there.
//
// Storage layout: <controlRoot>/stashes/<runId>/<glob-alternation>. The stash directory is a
// SIBLING of <controlRoot>/workspace/, so wiping the stage workspace does not destroy it.
pipeline {
    stages {
        stage("build") {
            writeFile(file = "src/keep.txt", text = "keep")
            // Same tree, same pattern, different extension: this one must stay behind.
            writeFile(file = "src/drop.pem", text = "drop")
            stash(name = "glob-alternation", includes = "src/**/*.{txt,md}")
        }
        stage("use") {
            // Wipe the workspace first, so what comes back can only have come from the stash.
            sh("rm -rf src")
            unstash(name = "glob-alternation")
            sh("test -f src/keep.txt")
            sh("test ! -f src/drop.pem")
        }
    }
}
