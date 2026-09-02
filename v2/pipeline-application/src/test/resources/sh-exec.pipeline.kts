// UAT-STEP-001: sh execution fixture
// returnStdout = true routes through the tee-gated wrapper so stdout is
// captured and emitted as EchoOutputCaptured (durable sh semantics).
pipeline {
    stages {
        stage("ShTest") {
            sh("echo hello from sh", returnStdout = true)
        }
    }
}
