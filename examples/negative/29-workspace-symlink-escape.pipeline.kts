//@ expect-exit 1
//@ expect-stderr escapes workspace
// A symlink inside the workspace is not a boundary.
//
// AGENTS.md section 1 makes "no workspace operation may escape the authorized
// root" non-negotiable, so this scenario pins the refusal on the side that
// destroys: a WRITE through a link that leaves the workspace.
//
// `normalize()` collapses `.` and `..` but never dereferences a symlink, so
// `<workspace>/escape/pwned.txt` starts with the workspace while `escape`
// points at the workspace's PARENT. Before c2d3da66 that passed the guard and
// `Files.createDirectories` + the atomic rename both landed outside.
//
// The runner asserts two things and cannot express a third: the run exits
// nonzero, and the refusal names the escape. That second directive is what
// separates this from a scenario that merely crashes -- a pipeline that died
// of anything else, anywhere, would fail here too.
//
// The workspace's parent is `$PWD/..` because `sh` runs with the workspace as
// its cwd. Nothing outside this scenario's private directory is touched, and
// nothing is created there: the guard throws before any effect.
pipeline {
    stages {
        stage("workspace-symlink-escape") {
            // The link is the whole attack. A relative target is enough, so
            // the scenario needs no absolute path and no knowledge of where
            // the runner put it.
            sh("ln -s .. escape")
            sh("test -d escape && echo 'link resolves outside the workspace'")

            writeFile("escape/pwned.txt", "PWNED")
        }
    }
}
