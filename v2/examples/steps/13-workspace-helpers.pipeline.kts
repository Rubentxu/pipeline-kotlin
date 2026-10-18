pipeline {
    stages {
        stage("workspace-helpers") {
            val dir = pwd()
            echo("current dir: " + dir)
            val unix = isUnix()
            echo("is unix: " + unix.toString())
            timestamps {
                echo("timestamped output")
            }
            waitUntil(initialRecurrencePeriod = 100) {
                true
            }
            echo("waitUntil done")
        }
    }
}
