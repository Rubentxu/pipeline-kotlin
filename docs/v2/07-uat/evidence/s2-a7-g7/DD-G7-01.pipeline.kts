pipeline {
    stages {
        stage("dd-g7-01") {
            sh("mkdir -p sub/inner && echo hello > file-a.txt && echo world > sub/file-b.txt && echo deep > sub/inner/file-c.txt")
            deleteDir(".")
        }
    }
}
