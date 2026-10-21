//@ expect-stdout current dir:
//@ expect-stdout temporary dir:
//@ expect-stdout is unix: true
//@ expect-stdout timestamped output
pipeline {
    stages {
        stage("workspace-helpers") {
            val dir = pwd()
            echo("current dir: " + dir)
            val temporary = pwd(tmp = true)
            echo("temporary dir: " + temporary)
            val unix = isUnix()
            echo("is unix: " + unix.toString())
            timestamps {
                echo("timestamped output")
            }
        }
    }
}
