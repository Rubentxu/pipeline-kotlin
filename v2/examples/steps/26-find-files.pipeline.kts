// The subject is enumeration by glob: a small tree is created and then read
// back through the typed step with two glob shapes, one for direct children and
// one recursive.
//
// The step's RETURNED SET is not asserted, and cannot be: the DSL is
// declarative, so a step's result is never handed back to the script and nothing
// it computed can reach stdout. What the checks below do assert is the shape the
// step is asked to enumerate -- one file at the top, one in each subdirectory --
// against the shell, so a step that silently scanned nothing would be answering a
// question the tree does not pose. Pinning the returned set itself needs an
// instrument that can read the step's output, which the launcher does not have.
import dev.rubentxu.pipeline.v2.sdk.utilities.step.findFiles

pipeline {
    stages {
        stage("find-files") {
            // 1) A directory tree with one file at the top and one in each of
            //    two subdirectories, so the two glob shapes cannot agree by
            //    accident.
            sh("""
                mkdir -p build/utils/scan/sub
                echo 'one' > build/utils/scan/a.txt
                echo 'two' > build/utils/scan/b.dat
                echo 'three' > build/utils/scan/sub/c.txt
                echo 'four' > build/utils/scan/sub/d.txt
            """.trimIndent())

            // 2) Direct children only: one file, because the other two live in
            //    subdirectories.
            findFiles(base = "build/utils/scan", glob = "*.txt")
            sh("test \"$(ls build/utils/scan/*.txt | wc -l)\" -eq 1")

            // 3) Recursive: the glob crosses the directory boundary.
            findFiles(base = "build/utils/scan", glob = "**/*.txt")

            // 4) Cross-check against the shell. Three .txt files exist (a.txt
            //    plus one in each subdirectory), so the listing must have three
            //    lines; comparing the count is what makes the step's answer
            //    falsifiable.
            sh("test \"$(find build/utils/scan -name '*.txt' | wc -l)\" -eq 3")
        }
    }
}
