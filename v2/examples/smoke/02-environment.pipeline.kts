pipeline {
    stages {
        stage("build") {
            withEnv(listOf("KEY=value", "BUILD_VERSION=1.0.0")) {
                echo("building...")
                // Inside the block on purpose: `withEnv` scopes the variables to
                // it, so the assertion has to observe them from where a step
                // would actually see them. Placed outside, it would pass on an
                // empty environment and prove nothing about `withEnv`.
                //
                // `${'$'}` rather than a bare `$`: this is a Kotlin raw string,
                // so a plain `$KEY` is a Kotlin template reference and the
                // scenario fails to COMPILE with "Unresolved reference" instead
                // of failing for the reason it is about. Written bare it once
                // did exactly that, which is a red that measured my typo and
                // not `withEnv`.
                sh("""test "${'$'}KEY" = value && test "${'$'}BUILD_VERSION" = 1.0.0""")
            }
        }
    }
}
