
import dev.rubentxu.pipeline.v2.domain.FailureKind
// UAT-STEP-003: error abort fixture
pipeline {
    stages {
        stage("ErrorTest") {
            error("boom", FailureKind.USER)
        }
    }
}
