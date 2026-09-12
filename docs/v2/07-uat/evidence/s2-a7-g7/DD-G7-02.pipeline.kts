pipeline {
    stages {
        stage("dd-g7-02") {
            sh("echo payload > payload.txt && mkdir -p payload-dir && echo x > payload-dir/nested.txt")
            deleteDir(".")
        }
    }
}
