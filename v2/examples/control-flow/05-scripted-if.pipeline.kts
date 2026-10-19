pipeline {
    stages {
        stage("conditional") {
            script {
                val x = 1
                if (x > 0) {
                    echo("x is positive")
                } else {
                    echo("x is not positive")
                }
            }
        }
    }
}
