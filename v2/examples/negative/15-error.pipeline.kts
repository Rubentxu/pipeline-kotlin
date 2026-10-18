
import dev.rubentxu.pipeline.v2.domain.FailureKind
pipeline {
    stages {
        stage("error-step") {
            error("test error message", failureKind = FailureKind.USER)
        }
    }
}
