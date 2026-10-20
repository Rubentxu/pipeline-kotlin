pipeline {
    stages {
        stage("sleep-step") {
            sleep(1)
            echo("woke")
        }
    }
}
