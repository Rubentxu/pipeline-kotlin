// Kotlin control flow inside a stage: the script {} block runs real Kotlin
// (conditions, loops, vals) on the scripting host.
// Run: examples/run.sh 04-kotlin-control-flow.pipeline.kts
pipeline {
    stages {
        stage("conditional") {
            script {
                val files = listOf("build.gradle.kts", "settings.gradle.kts", "README.md")
                val missing = files.filter { !java.io.File(it).exists() }
                if (missing.isEmpty()) {
                    echo("all ${files.size} expected files present")
                } else {
                    echo("missing: $missing")
                }
            }
        }
        stage("countdown") {
            script {
                for (n in 3 downTo 1) {
                    echo("countdown $n")
                }
                echo("liftoff")
            }
        }
    }
}
