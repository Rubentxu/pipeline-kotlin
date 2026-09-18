// WU-LPR-064 failure path: DEMO_BROKEN=true must fail the pipeline with typed outcome.
pipeline {
    stages {
        stage("build") {
            sh("DEMO_BROKEN=true node --test")
        }
    }
}
