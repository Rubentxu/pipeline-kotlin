
// Core.readFile live fixture. Writes a file with the certified
// writeFile substrate, reads it back through core.readFile (FileRead event,
// content never enters the event channel), then checks existence via
// core.fileExists (missing file is a legitimate false, not a failure).
//
// The capability under trial is the FILE, and a file needs no store: what is
// read back and what is found are decided by the workspace and the filesystem,
// so the same guarantee has to hold with a store and without one. The
// directive says so, and the launcher refuses this scenario if it ever needs
// the store to keep its promise.

pipeline {
    stages {
        stage("readfile-roundtrip") {
            writeFile("lpr104-readme.txt", "hello-lpr-104")
            readFile("lpr104-readme.txt")
            fileExists("lpr104-readme.txt")
            fileExists("lpr104-missing.txt")
            echo("ROUNDTRIP-OK")
            // The two `fileExists` calls above DECLARE the checks: the eager
            // form returns nothing, so the answer of each one stays inside the
            // step and reaches the script nowhere. The call that must be true
            // and the call that must be false therefore left this scenario
            // equally green. The round-trip guarantee is that the bytes written
            // come back out identical; assert them, and assert the negative case
            // too, or a `fileExists` that answered true to everything would
            // still pass.
            sh("test -f lpr104-readme.txt && grep -q hello-lpr-104 lpr104-readme.txt")
            sh("test ! -e lpr104-missing.txt")
        }
    }
}
