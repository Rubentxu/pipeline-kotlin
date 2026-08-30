pipeline {
    stages {
        stage("credentials-bindings") {
            // Exercise all 5 NEW binding kinds ( widened in ML-R10 / commit 682d4e5 )
            // plus the 2 pre-existing kinds for a complete 7-kind corpus entry.
            // DSL syntax: reconcile against fixture 08-withEnv + widened factories.
            // Rule 13 ${'$'}VAR — shell variable expansion in strings.

            withCredentials(listOf(
                // Kind.STRING (pre-existing)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.string(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("string-creds"),
                    "API_KEY"
                ),
                // Kind.USERNAME_PASSWORD (pre-existing)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.usernamePassword(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("userpass-creds"),
                    "DB_USER",
                    "DB_PASS"
                ),
                // Kind.SSH_USER_PRIVATE_KEY (NEW in ML-R10)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.sshUserPrivateKey(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("ssh-creds"),
                    "SSH_KEY_FILE"
                ),
                // Kind.FILE (NEW in ML-R10)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.file(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("file-creds"),
                    "SECRET_FILE"
                ),
                // Kind.CERTIFICATE (NEW in ML-R10)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.certificate(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("cert-creds"),
                    "KEYSTORE_PATH"
                ),
                // Kind.ZIP (NEW in ML-R10)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.zip(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("zip-creds"),
                    "ZIP_PATH"
                ),
                // Kind.USERNAME_COLON_PASSWORD (NEW in ML-R10)
                dev.rubentxu.pipeline.v2.dsl.StepSpec.CredentialsBinding.usernameColonPassword(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("ucp-creds"),
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
