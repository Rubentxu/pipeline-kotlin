pipeline {
    stages {
        stage("workspace-helpers") {
            val dir = pwd()
            echo("current dir: " + dir)
            val unix = isUnix()
            echo("is unix: " + unix.toString())
            timestamps {
                // The scope ANNOUNCES itself: it publishes the TimestampsEntered and
                // TimestampsExited events of the scope, and it DECORATES NOTHING. The
                // output of the body travels as the process produced it.
                echo("output inside the announced scope")
            }
            // The predicate of a wait is the OUTCOME of the steps its body declares: a
            // body that declares no step is refused while the script is written. The
            // marker makes the step of the body succeed on the first poll.
            sh("touch marker-workspace-helpers")
            waitUntil(initialRecurrencePeriod = 100) {
                sh("test -f marker-workspace-helpers && echo READY")
            }
            sh("rm marker-workspace-helpers")
            echo("waitUntil done")
        }
    }
}
