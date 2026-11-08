// The subject is a COUNT: the body of a repeated block runs exactly as
// many times as the block was asked for.
//
// A count needs no store: how many times the body ran is decided by the
// block, and the file it wrote is the witness. A bare `echo` would stay
// green for a block that ran once, or not at all, which is the same
// defect the block exists to rule out.
//
// The witness is a LINE COUNT and not a file: the body appends a line per
// iteration, so `wc -l` is the number the block promised. The file
// itself is also required to exist, so a run that produced nothing at all
// cannot be read as "the count happened to be zero".
import example.block.repeatBlock

pipeline {
    stages {
        stage("repeat") {
            repeatBlock(2) {
                sh("echo iteration | tee -a iterations.txt")
            }
            sh("test \"$(wc -l < iterations.txt)\" -eq 2")
            sh("mkdir -p /workspace/out && cp iterations.txt /workspace/out/canary-iterations.txt")
        }
    }
}
