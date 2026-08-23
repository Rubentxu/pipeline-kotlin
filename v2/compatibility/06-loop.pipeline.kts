pipeline {
    stages {
        stage("loop") {
            script {
                for (i in 1..3) {
                    echo("iteration $i")
                }
            }
        }
    }
}
