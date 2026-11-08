// dir restaura cwd tras error. El primer dir() falla (exit 1),
// el segundo dir() debe partir del workspace raiz (no del subdirectorio
// fallido) y verificarlo escribiendo pwd.
pipeline {
    stages {
        stage("dir-restore") {
            dir("errdir") {
                sh("exit 1")
            }
            dir("chk") {
                sh("pwd | grep -qx /workspace && printf 'cwd-restored\\n' > /workspace/out/marker.txt")
            }
        }
    }
}
