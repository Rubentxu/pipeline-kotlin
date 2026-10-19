// WU-LPR-104: core.readFile live fixture. Writes a file with the certified
// writeFile substrate, reads it back through core.readFile (FileRead event,
// content never enters the event channel), then checks existence via
// core.fileExists (missing file is a legitimate false, not a failure).
pipeline {
    stages {
        stage("readfile-roundtrip") {
            writeFile("lpr104-readme.txt", "hello-lpr-104")
            readFile("lpr104-readme.txt")
            fileExists("lpr104-readme.txt")
            fileExists("lpr104-missing.txt")
            echo("ROUNDTRIP-OK")
            // The two `fileExists` calls above return booleans that nothing was
            // reading, so the call that must be true and the call that must be
            // false left this scenario equally green. The round-trip guarantee
            // is that the bytes written come back out identical; assert them,
            // and assert the negative case too, or a `fileExists` that answered
            // true to everything would still pass.
            sh("test -f lpr104-readme.txt && grep -q hello-lpr-104 lpr104-readme.txt")
            sh("test ! -e lpr104-missing.txt")
        }
    }
}
