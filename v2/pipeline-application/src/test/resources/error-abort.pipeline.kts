// UAT-STEP-003: error abort fixture
pipeline {
    stages {
        stage("ErrorTest") {
            error("boom", "SCRIPT")
        }
    }
}
