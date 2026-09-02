// Multi-step fixture: 2 stages x (echo + sh).
// INC-R8-ARC-001 historical note: under the legacy record-only walker the
// `make` steps were never really executed (simulated StepFailed). Under the
// Single Runtime Spine (LF-0208) sh steps really execute, so the fixture
// uses succeeding commands to characterise the success-path timeline.
pipeline {
    stages {
        stage("build") {
            echo("compiling")
            sh("echo build-ok")
        }
        stage("test") {
            echo("testing")
            sh("echo test-ok")
        }
    }
}
