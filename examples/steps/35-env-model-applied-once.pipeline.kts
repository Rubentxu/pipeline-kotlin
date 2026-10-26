pipeline {
    stages {
        stage("env") {
            // The environment model is applied ONCE per journey.
            //
            // The model prepends the toolchain directory to PATH, and it is NOT idempotent:
            // applying it to an environment it already transformed prepends the directory a
            // second time. The durable journey used to apply it twice -- once in the
            // application, and once more in the runtime that composes the child's
            // environment -- so the child of a durable `sh` saw `/opt/jdk/bin` TWICE in its
            // PATH, while the child of the non-durable journey saw it once. The duplicate was
            // invisible under the local sandbox profile, which rebuilds the path from the
            // keep-set, and visible under the default one, which does not.
            //
            // The expected value is the one the non-durable journey already produces: the
            // model applied once to the declared environment. Asserting the EXACT path (and
            // not "contains the directory") is what makes the row discriminating: a lost
            // element and a duplicated one are both caught.
            //
            // `${'$'}` rather than a bare `$`: this is a Kotlin raw string, so a plain
            // `$PATH` is a Kotlin template reference and the scenario fails to COMPILE with
            // "Unresolved reference" instead of failing for the reason it is about.
            withEnv(mapOf("JAVA_HOME" to "/opt/jdk", "PATH" to "/usr/bin:/bin")) {
                sh(
                    """test "${'$'}PATH" = "/opt/jdk/bin:/usr/bin:/bin" || { printf 'OBSERVED_PATH=%s\n' "${'$'}PATH"; exit 1; }""",
                )
            }
        }
    }
}
