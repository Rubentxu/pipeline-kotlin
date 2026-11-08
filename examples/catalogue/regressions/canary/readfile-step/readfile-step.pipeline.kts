// The subject is the READ side of the file steps: `readFile` and
// `fileExists`, in BOTH polarities.
//
// A `fileExists` that answered true to everything would leave every
// canary that used it green, so the missing file is asked about too, and
// its absence is asserted. The `readFile` is there because reading is a
// different capability from asking: a step that existed but never read
// would satisfy an existence check.
//
// The bytes are checked with `grep -qx`, line-exact, so a file that holds
// something else under the same name does not pass.
pipeline {
    stages {
        stage("roundtrip") {
            sh("mkdir -p out && printf 'CANARY-READFILE\\n' > out/canary-readfile.txt")
            readFile("out/canary-readfile.txt")
            fileExists("out/canary-readfile.txt")
            fileExists("out/canary-absent.txt")
            sh("grep -qx CANARY-READFILE out/canary-readfile.txt && test ! -e out/canary-absent.txt")
        }
    }
}
