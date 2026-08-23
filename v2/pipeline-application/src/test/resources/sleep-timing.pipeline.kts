// UAT-STEP-004: sleep timing fixture
pipeline {
    stages {
        stage("SleepTest") {
            sleep(1)
        }
    }
}
