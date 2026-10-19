//@ expect-stdout post-2

// The subject is a milestone step reached through the open registry rather than
// by a hard-wired call. Each milestone writes a durable trace entry, and the
// two ordinals here must both land before the pipeline reaches the final echo:
// that the run finishes is what tells us neither of them threw.
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