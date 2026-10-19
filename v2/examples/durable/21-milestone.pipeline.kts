// 21-milestone.pipeline.kts — S2-A9 / G5 installed-CLI canary
// Real .pipeline.kts using core.milestone through the open-world registry path.
// Validates that after G5 destructive (subtype/branch/constant/metadata row/dispatcher
// file removed), the DSL `milestone(ordinal, label)` still produces a correct durable
// trace via StepSpec.RegistryStepSpec + CoreMilestoneStep.
pipeline {
    stages {
        stage("milestones") {
            echo("pre-1")
            milestone(ordinal = 1, label = "post-error")
            echo("between")
            milestone(ordinal = 2, label = "post-build")
            echo("post-2")
        }
    }
}