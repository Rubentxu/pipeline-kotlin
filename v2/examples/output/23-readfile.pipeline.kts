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
        }
    }
}
