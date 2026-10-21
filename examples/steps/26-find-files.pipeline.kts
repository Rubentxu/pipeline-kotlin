// The subject is the SIDE-EFFECT contract of `core-utils.findFiles`. The step is
// declared `Effect.READ_ONLY` and returns a typed value the DSL does not
// expose, so the guarantee the harness can observe is "what files survived the
// step", not "what files did the step enumerate". A finder that enumerated
// nothing passes the latter; a finder that touched anything -- created a
// sidecar, deleted a file, rewrote a byte -- fails this one, because the
// snapshot taken afterwards has a hash the snapshot taken before did not.
//
// Two assertions back the same read-only contract from two angles:
//
//   * The sha256sum of every file's bytes, sorted by path, proves no file
//     was added, removed or modified by the step.
//
//   * The plain file count proves the directory layout did not shrink beneath
//     a step that silently emptied a path it had been told to scan.
//
// Both must hold across every glob shape the scenario exercises, including
// the zero-match case (`*.nope`) which is the loosest match a finder can
// face: a step that decided "nothing matched, I will tidy up" would slip
// past a glob selector and not past a tree-integrity hash.
//
// The strong guarantee -- "this glob enumerates exactly the files it should" --
// remains covered where the typed output IS observable: the unit test that
// pins `FindFilesOutput` directly.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles

pipeline {
    stages {
        stage("find-files") {
            // 1) A small tree: one .txt at the top, one in the only
            //    subdirectory; the four .dat/.txt files are listed by the
            //    final assertion below.
            sh("""
                mkdir -p build/utils/scan/sub
                echo 'one'   > build/utils/scan/a.txt
                echo 'two'   > build/utils/scan/b.dat
                echo 'three' > build/utils/scan/sub/c.txt
                echo 'four'  > build/utils/scan/sub/d.txt
            """.trimIndent())

            // 2) Snapshot A: every file under the base, content-hashed,
            //    sorted so the hash is independent of walker order.
            sh("""
                (cd build/utils/scan && find . -type f -print0 \
                    | LC_ALL=C sort -z \
                    | xargs -0 sha256sum) > snapshot.before
            """.trimIndent())

            // 3) Three glob shapes against the same tree. The first two
            //    have results; the third matches nothing, on purpose,
            //    because it is the loosest case a finder can face: a step
            //    that decided "no result, I will tidy up" would slip past
            //    a glob selector and not past a snapshot.
            findFiles(base = "build/utils/scan", glob = "*.txt")
            findFiles(base = "build/utils/scan", glob = "**/*.txt")
            findFiles(base = "build/utils/scan", glob = "**/*.nope")

            // 4) Snapshot B, identical recipe. Diff is the assertion.
            sh("""
                (cd build/utils/scan && find . -type f -print0 \
                    | LC_ALL=C sort -z \
                    | xargs -0 sha256sum) > snapshot.after
            """.trimIndent())

            //    No file was created, deleted, or modified: any of those
            //    would shift the sorted listing and the per-file content
            //    hash would no longer match its counterpart.
            sh("diff -u snapshot.before snapshot.after")

            //    The four files written in step 1 are still the four files
            //    in step 4: a step that wrote to an existing path with the
            //    same name (and the same bytes it already had) would pass
            //    the sha256sum check above while shrinking or growing the
            //    entry count. This row catches that as a separate angle.
            sh("test \"$(find build/utils/scan -type f | wc -l)\" -eq 4")
        }
    }
}
