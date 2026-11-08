// The subject is the WRITE step of the DSL, and its guarantee is that what
// it writes is there to be read BACK, in a DIFFERENT stage.
//
// The two stages are the point. A write and its read in the same stage
// would prove the step resolved and did something; a write in one stage
// and a read in another proves the workspace is what the next stage sees,
// which is the contract every certification depends on.
//
// The read is `grep -qx`, line-exact: a file containing the marker
// and more would not satisfy a guarantee about the marker, and a
// substring match would let it through.
pipeline {
    stages {
        stage("write") {
            writeFile(file = "out/canary-writefile.txt", text = "CANARY-WRITEFILE")
        }
        stage("read-back") {
            sh("grep -qx CANARY-WRITEFILE out/canary-writefile.txt")
        }
    }
}
