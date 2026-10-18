import example.block.repeatBlock

pipeline {
    stages {
        stage("Repeat") {
            repeatBlock(2) {
                sh("echo iteration")
            }
        }
    }
}
