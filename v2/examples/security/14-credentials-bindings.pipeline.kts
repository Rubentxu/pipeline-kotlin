//@ credentials string-creds secret-text
//@ credentials userpass-creds username-password
//@ credentials ssh-creds ssh-private-key
//@ credentials file-creds secret-file
//@ credentials cert-creds certificate
//@ credentials zip-creds zip
//@ credentials ucp-creds username-colon-password
//@ expect-stdout 7 credential binding kinds resolved
//
// The subject is the seven binding kinds, and the launcher seeds each of them
// into this scenario's own store before the run. The seeded values are
// synthetic fixtures: nothing here asserts WHAT a binding resolved to, because
// the fixture is not a fact about the product. What is asserted is that every
// kind went from "declared in the DSL" to "a value the step could read".
//
// That distinction matters more than it looks. A binding that silently resolved
// to an empty string would satisfy any assertion about shape and fail only here,
// which is why the check below is on non-emptiness rather than on the names.
//
// Shell variable expansion inside a string. The correct Kotlin escape is
// `\${VAR}` (a single backslash before `$`, then the braced identifier). The
// form `\${'$'}VAR` looks similar but is a TRAP: Kotlin compiles `${'$'}` to a
// literal `$`, then `VAR` is adjacent text, and bash receives `${'$'}VAR` which
// it rejects as `sustitución errónea`.
pipeline {
    stages {
        stage("credentials-bindings") {
            withCredentials(listOf(
                // STRING
                StepSpec.CredentialsBinding.string(
                    "string-creds",
                    "API_KEY"
                ),
                // USERNAME_PASSWORD
                StepSpec.CredentialsBinding.usernamePassword(
                    "userpass-creds",
                    "DB_USER",
                    "DB_PASS"
                ),
                // SSH_USER_PRIVATE_KEY
                StepSpec.CredentialsBinding.sshUserPrivateKey(
                    "ssh-creds",
                    "SSH_KEY_FILE"
                ),
                // FILE
                StepSpec.CredentialsBinding.file(
                    "file-creds",
                    "SECRET_FILE"
                ),
                // CERTIFICATE
                StepSpec.CredentialsBinding.certificate(
                    "cert-creds",
                    "KEYSTORE_PATH"
                ),
                // ZIP
                StepSpec.CredentialsBinding.zip(
                    "zip-creds",
                    "ZIP_PATH"
                ),
                // USERNAME_COLON_PASSWORD
                StepSpec.CredentialsBinding.usernameColonPassword(
                    "ucp-creds",
                    "U_P"
                )
            )) {
                sh("echo API_KEY=\${API_KEY} DB_USER=\${DB_USER} SSH_KEY_FILE=\${SSH_KEY_FILE} SECRET_FILE=\${SECRET_FILE} KEYSTORE_PATH=\${KEYSTORE_PATH} ZIP_PATH=\${ZIP_PATH} U_P=\${U_P}")
                // The first version of this line echoed "resolved successfully"
                // unconditionally, so it printed that whether or not any binding
                // had resolved: seven empty strings satisfy `echo name=` and
                // still exit 0. A success message is not evidence of success.
                // `-n` is the strongest honest check available here.
                //
                // Uses the `\${VAR}` form documented above, not `${'$'}VAR`,
                // which bash rejects.
                sh("test -n \"\${API_KEY}\" && test -n \"\${DB_USER}\" && test -n \"\${SSH_KEY_FILE}\" && test -n \"\${SECRET_FILE}\" && test -n \"\${KEYSTORE_PATH}\" && test -n \"\${ZIP_PATH}\" && test -n \"\${U_P}\"")
                echo("7 credential binding kinds resolved")
            }
        }
    }
}