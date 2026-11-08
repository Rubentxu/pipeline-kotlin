// The subject is a READ-ONLY step: enumerating files must not change the
// tree it enumerated.
//
// The capability is `findFiles`, and its guarantee is that it only looks.
// A finder that created a sidecar, deleted a file or rewrote a byte would
// satisfy an enumeration check and still change the workspace, and the
// next stage would certify a tree the finder had shaped.
//
// The witness is a snapshot: the sha256 of every file, sorted by path, so
// the comparison does not depend on the order the filesystem returns. A
// file that appeared, vanished or changed shifts the listing and the
// comparison fails. The file COUNT is checked too, because a step that
// rewrote a file with the same bytes would leave the hash alone and still
// be a tree that moved.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles

pipeline {
    stages {
        stage("find") {
            sh("mkdir -p tree/sub && printf 'one\\n' > tree/a.txt && printf 'two\\n' > tree/sub/b.txt && printf 'three\\n' > tree/sub/c.dat")
            sh("(cd tree && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > snapshot.before")
            findFiles(base = "tree", glob = "**/*.txt")
            findFiles(base = "tree", glob = "**/*.nope")
            sh("(cd tree && find . -type f -print0 | LC_ALL=C sort -z | xargs -0 sha256sum) > snapshot.after")
            sh("diff -u snapshot.before snapshot.after && test \"$(find tree -type f | wc -l)\" -eq 3")
            sh("mkdir -p /workspace/out && cp snapshot.after /workspace/out/canary-findfiles.txt")
        }
    }
}
