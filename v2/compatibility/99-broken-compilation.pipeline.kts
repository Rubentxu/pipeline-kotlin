// This script is intentionally broken to test compilation failure exit code
pipeline {
    stages {
        stage("broken") {
            // Intentionally reference a non-existent type to trigger compilation failure
            withCredentials(listOf(
                dev.rubentxu.pipeline.v2.domain.CredentialsId("broken-creds"),
                "VAR_NAME"
            )) {
                sh("echo test")
            }
        }
    }
}
