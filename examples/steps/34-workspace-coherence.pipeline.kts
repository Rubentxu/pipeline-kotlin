//@ expect-stdout WROTE_HERE
//@ expect-stdout RESTORED
//@ expect-stdout SCOPE_CLEAN
//@ expect-stdout VICTIM_GONE
//@ expect-stdout ROOT_SURVIVED
//@ expect-stdout ROOT_VICTIM_SURVIVED
//@ expect-stdout ROOT_NOT_POLLUTED

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
// The stage root holds `at-root.txt` and its own `victim/`. The `dir("inner")`
// scope holds its own files and its own `victim/keep.txt`. Each of the four
// Steps gets a check that can only pass if it resolved against the EFFECTIVE
// cwd, and each check is placed so that ONE broken adapter takes down ONE
// guarantee and leaves the other six green:
//
//   - `writeFile` — `written.txt` must land in the scope. A `writeFile` that
//     resolved against the stage workspace would leave the scope without it.
//     The check runs IMMEDIATELY, before the stash, so a broken `writeFile`
//     cannot abort the run: the stash still has `anchor.txt` to take.
//   - `stash` — the stash must take the SCOPE's content, not the stage
//     workspace's. A stash that took the stage workspace would carry
//     `at-root.txt` instead, and the restore would put the root's file INSIDE
//     the scope: `RESTORED` and `SCOPE_CLEAN` both go out.
//   - `unstash` — the restore must land in the scope, not above it. A restore
//     that landed in the stage workspace would leave `anchor.txt` outside:
//     `RESTORED` and `ROOT_NOT_POLLUTED` both go out.
//   - `deleteDir` — the scope's `victim` must be wiped and the root's must
//     survive. A `deleteDir` that resolved against the stage workspace would
//     wipe the ROOT's `victim` instead: `VICTIM_GONE` and
//     `ROOT_VICTIM_SURVIVED` both go out.
//
// The two `victim/` trees exist at both levels ON PURPOSE. A single one would
// make a deleteDir against the wrong base either delete the scope's file (and
// hide the regression) or fail on a missing directory (and abort the run
// before the check could say anything). With both, the wrong base has
// something to delete, the scope's copy survives, and the check can speak.
//
// `anchor.txt` and `victim/keep.txt` are created with `sh` on purpose: they
// must exist whatever the file Steps do, so that a broken `writeFile` cannot
// leave the stash with nothing to take and abort the run before the report
// can name the guarantee that was lost.
//
// ## Why the checks do not abort the run
//
// Each one is `test … && echo MARKER || echo failure`, so a failed check
// prints its own failure token and the run carries on to the next check.
// That is deliberate: the report then names EXACTLY which guarantees are
// missing, instead of stopping at the first one and hiding the rest. The
// scenario's verdict comes from the `expect-stdout` directives — a missing
// marker is a red row — and the failure tokens deliberately avoid every
// expected marker, so a failure can never be mistaken for a pass.
//
// The failure tokens are unique per leg, and that is what makes the four
// regressions tell each other apart:
//
//   - a broken `writeFile`  loses WROTE_HERE
//   - a broken `stash`      loses RESTORED and SCOPE_CLEAN
//   - a broken `unstash`    loses RESTORED and ROOT_NOT_POLLUTED
//   - a broken `deleteDir`  loses VICTIM_GONE and ROOT_VICTIM_SURVIVED
pipeline {
    stages {
        stage("coherence") {
            // At the STAGE workspace root. If any Step inside the scope resolved
            // against the stage workspace, this is the file that would be eaten.
            writeFile(file = "at-root.txt", text = "ROOT")
            // The root's own `victim`, created with `sh` so it exists whatever the
            // file Steps do: this one is the counterpart of the scope's.
            sh("mkdir -p victim && echo KEEP-ROOT > victim/keep.txt")

            dir("inner") {
                // The scope's anchor, created with `sh`: the stash always has
                // something to take, so a broken `writeFile` cannot abort the run.
                sh("echo INNER > anchor.txt")
                // The scope's `victim`, also created with `sh`: the scope's own
                // base, which no file-Step regression can move.
                sh("mkdir -p victim && echo KEEP-SCOPE > victim/keep.txt")

                // The file Step's own target, under its own name so that it is
                // distinguishable from the stash's anchor.
                writeFile(file = "written.txt", text = "WRITTEN")
                sh("test -f written.txt && echo WROTE_HERE || echo LEG_write_scope")

                // The stash must see the SCOPE, not the stage workspace: it has to
                // carry `anchor.txt`, and it must NOT carry `at-root.txt`.
                stash(name = "coherence", includes = "*.txt")
                sh("rm -f anchor.txt")
                unstash(name = "coherence")
                sh("test -f anchor.txt && echo RESTORED || echo LEG_restore_scope")

                // The stash must not have carried the stage root's file INTO the
                // scope: the scope is a different directory, and a file that lives
                // above it does not come down with the stash. Checked from inside
                // the scope, so the path resolves against the scope's own base.
                sh("test ! -e at-root.txt && echo SCOPE_CLEAN || echo LEG_root_leaked")

                // The delete must hit the scope's own subdirectory.
                deleteDir("victim")
                sh("test ! -f victim/keep.txt && echo VICTIM_GONE || echo LEG_delete_scope")
            }

            // The scope's Steps never reached the stage root's own files.
            sh("test -f at-root.txt && echo ROOT_SURVIVED || echo LEG_root_lost")
            sh("test -f victim/keep.txt && echo ROOT_VICTIM_SURVIVED || echo LEG_root_victim_lost")
            // And the scope's file did not end up ABOVE the scope.
            sh("test ! -f anchor.txt && echo ROOT_NOT_POLLUTED || echo LEG_restore_escaped")
        }
    }
}
