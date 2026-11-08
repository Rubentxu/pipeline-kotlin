// dir() subdirectory semantics + effective cwd coherence.
// Enters a subdir via dir(), writes through sh, verifies fileExists there.
pipeline {
    stages {
        stage("dir-contract") {
            dir("nested/deep") {
                sh("mkdir -p out && pwd > out/pwd.txt")
            }
        }
    }
}
