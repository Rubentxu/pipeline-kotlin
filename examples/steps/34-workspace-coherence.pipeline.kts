//@ expect-stdout WROTE_HERE
//@ expect-stdout RESTORED
//@ expect-stdout VICTIM_GONE
//@ expect-stdout ROOT_SURVIVED

// The four workspace-aware Steps must agree on ONE base: the effective working
// directory of the `dir(...)` scope they run in.
//
// The invariant is not written down anywhere as a test, and it has already
// broken once: the KDoc of `DeleteDirOperationsAdapter` records that it "used
// to re-derive the stage workspace from the control root, so inside a
// `dir(...)` scope the two Steps disagreed about where 'sub' was". A defect
// that already happened once is not protected by anything, so this scenario
// is the instrument.
//
// ## How it discriminates
//
// The stage root holds `at-root.txt`. The `dir("inner")` scope holds
// `in-inner.txt` and `victim/keep.txt`. Each of the four Steps is given a
// check that can only pass if it resolved against the EFFECTIVE cwd:
//
//   - `writeFile` — inside the scope, `in-inner.txt` must be there. A
//     `writeFile` that resolved against the stage workspace would leave the
//     scope empty and put the file at the stage root instead.
//   - `stash` + `unstash` — the stash must take the scope's content, and the
//     unstash must put it back in the scope. A stash that took the stage
//     workspace would carry `at-root.txt` instead, and `in-inner.txt` would
//     stay missing after the restore.
//   - `deleteDir` — `victim` must be wiped. A `deleteDir` that resolved
//     against the stage workspace would look for `<stage>/victim`, find
//     nothing there, and leave `<scope>/victim/keep.txt` alive.
//
// And the last marker closes the other direction: the stage root's own file
// survives, so the scope's Steps did not reach out and touch it.
//
// The markers are `echo`ed only AFTER the corresponding `sh` check passed, so
// a Step that resolved against the wrong base fails the run outright — the
// scenario goes red on the check, not on a missing marker.
pipeline {
    stages {
        stage("coherence") {
            // At the STAGE workspace root. If any Step inside the scope resolved
            // against the stage workspace, this is the file that would be eaten.
            writeFile(file = "at-root.txt", text = "ROOT")

            dir("inner") {
                writeFile(file = "in-inner.txt", text = "INNER")
                writeFile(file = "victim/keep.txt", text = "KEEP")

                sh("test -f in-inner.txt")
                echo("WROTE_HERE")

                // The stash must see the SCOPE, not the stage workspace: it has to
                // carry `in-inner.txt`, and it must NOT carry `at-root.txt`.
                stash(name = "coherence", includes = "*.txt")
                sh("rm -f in-inner.txt")
                unstash(name = "coherence")
                sh("test -f in-inner.txt")
                echo("RESTORED")

                // The delete must hit the scope's own subdirectory. Resolved
                // against the stage workspace it would find nothing to delete.
                deleteDir("victim")
                sh("test ! -f victim/keep.txt")
                echo("VICTIM_GONE")
            }

            // The scope's Steps never reached the stage root's file.
            sh("test -f at-root.txt")
            echo("ROOT_SURVIVED")
        }
    }
}
