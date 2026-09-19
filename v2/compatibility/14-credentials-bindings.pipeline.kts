pipeline {
    stages {
        stage("credentials-bindings") {
            // Exercise all 5 NEW binding kinds ( widened in ML-R10 / commit 682d4e5 )
            // plus the 2 pre-existing kinds for a complete 7-kind corpus entry.
            // DSL syntax: reconcile against fixture 08-withEnv + widened factories.
            // Rule 13: shell variable expansion in strings. The CORRECT Kotlin
            // escape is `\${VAR}` (a single backslash before `$`, then the
            // braced identifier). The form `\${'$'}VAR` looks similar but is
            // a TRAP: Kotlin compiles `${'$'}` to a literal `$`, then `VAR`
            // is adjacent text, and bash receives `${'$'}VAR` which it
            // rejects as `sustitución errónea`. See CHARACTERISATION.md §5.3
            // for the byte-level proof. The line below uses the safe form.

            withCredentials(listOf(
                // Kind.STRING (pre-existing) — pure DSL, no FQCN imports
                StepSpec.CredentialsBinding.string(
                    "string-creds",
                    "API_KEY"
                ),
                // Kind.USERNAME_PASSWORD (pre-existing) — pure DSL
                StepSpec.CredentialsBinding.usernamePassword(
                    "userpass-creds",
                    "DB_USER",
                    "DB_PASS"
                ),
                // Kind.SSH_USER_PRIVATE_KEY (NEW in ML-R10) — pure DSL
                StepSpec.CredentialsBinding.sshUserPrivateKey(
                    "ssh-creds",
                    "SSH_KEY_FILE"
                ),
                // Kind.FILE (NEW in ML-R10) — pure DSL
                StepSpec.CredentialsBinding.file(
                    "file-creds",
                    "SECRET_FILE"
                ),
                // Kind.CERTIFICATE (NEW in ML-R10) — pure DSL
                StepSpec.CredentialsBinding.certificate(
                    "cert-creds",
                    "KEYSTORE_PATH"
                ),
                // Kind.ZIP (NEW in ML-R10) — pure DSL
                StepSpec.CredentialsBinding.zip(
                    "zip-creds",
                    "ZIP_PATH"
                ),
                // Kind.USERNAME_COLON_PASSWORD (NEW in ML-R10) — pure DSL
                StepSpec.CredentialsBinding.usernameColonPassword(
                    "ucp-creds",
                    "U_P"
                )
            )) {
                // Verify shell expansion of injected env vars
                sh("echo API_KEY=\${API_KEY} DB_USER=\${DB_USER} SSH_KEY_FILE=\${SSH_KEY_FILE} SECRET_FILE=\${SECRET_FILE} KEYSTORE_PATH=\${KEYSTORE_PATH} ZIP_PATH=\${ZIP_PATH} U_P=\${U_P}")
                echo("All 7 credential binding kinds resolved successfully")
            }
        }
    }
}
