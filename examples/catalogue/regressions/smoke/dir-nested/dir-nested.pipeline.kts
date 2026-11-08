// dir nested + read/write/fileExists con hashes.
// Escribe en subdirectorios anidados via dir(), verifica lectura y existencia.
pipeline {
    stages {
        stage("dir-nested") {
            dir("a/b/c") {
                sh("printf 'payload-har006\\n' > payload.txt")
            }
            dir("a/b/c") {
                sh("cat payload.txt | grep -q payload-har006 && test -f payload.txt && mkdir -p /workspace/out && printf 'har006-ok\\n' > /workspace/out/marker.txt")
            }
        }
    }
}
