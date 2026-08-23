// Parallel execution fixture exercising parallel branches.

pipeline {
    stages {
        stage("ParallelTest") {
            agent("linux-agent")

            parallel {
                branch("branch-a") {
                    echo("Branch A step 1")
                    sh("echo 'Branch A running'")
                    sleep(1)
                    echo("Branch A step 2")
                }
                branch("branch-b") {
                    echo("Branch B step 1")
                    sh("echo 'Branch B running'")
                    sleep(1)
                    echo("Branch B step 2")
                }
                branch("branch-c") {
                    echo("Branch C step 1")
                    sh("echo 'Branch C running'")
                    sleep(1)
                    echo("Branch C step 2")
                }
            }

            echo("All branches complete")
        }
    }
}
