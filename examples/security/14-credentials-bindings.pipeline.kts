//@ credentials string-creds secret-text
//@ credentials userpass-creds username-password
//@ credentials ssh-creds ssh-private-key
//@ credentials file-creds secret-file
//@ credentials cert-creds certificate
//@ credentials zip-creds zip
//@ credentials ucp-creds username-colon-password
//@ expect-stdout 7 credential binding kinds resolved
//@ expect-transcript API_KEY=****
//@ expect-transcript NO_STORE_NEEDS_NO_KEY
//@ expect-transcript NO_STORE_LEAVES_NO_FILE
//@ expect-transcript NO_PASSPHRASE_IS_ITS_OWN_CODE
//@ expect-transcript WRONG_KEY_CHANGES_NOTHING
//@ expect-transcript WRONG_KEY_IS_NAMED
//@ expect-transcript MISSING_CREDENTIAL_IS_NAMED
// The subject is the seven binding kinds, and the launcher seeds each of them
// into this scenario's own store before the run. The seeded values are
// synthetic fixtures: nothing here asserts WHAT a binding resolved to, because
// the fixture is not a fact about the product. What is asserted is that every
// kind went from "declared in the DSL" to "a value the step could read".
// That distinction matters more than it looks. A binding that silently resolved
// to an empty string would satisfy any assertion about shape and fail only here,
// which is why the check below is on non-emptiness rather than on the names.
//
// The second half of this scenario is about the DOOR those bindings come
// through. The passphrase is the authorization to touch the store, so the
// question is what the product does when that authorization is wrong, and what
// it does when there is nothing behind the door at all. A store that answered
// a wrong passphrase as if nothing were wrong, or that let a wrong passphrase
// delete a credential, would make the whole surface decorative.
//
// Each check prints its own marker and the launcher names the marker that did
// not arrive, so a broken guarantee says which one it was.

// Shell variable expansion inside a string. The correct Kotlin escape is
// `\${VAR}` (a single backslash before `$`, then the braced identifier). The
// form `\${'$'}VAR` looks similar but is a TRAP: Kotlin compiles `${'$'}` to a
// literal `$`, then `VAR` is adjacent text, and bash receives `${'$'}VAR` which
// it rejects as `sustitución errónea`.

// The door is asked through the product's own entry point, which the launcher
// writes for this scenario and which carries this scenario's clock.
val product = System.getenv("PIPELINEK_E2E_PRODUCT")
    ?: error("PIPELINEK_E2E_PRODUCT is not set: this scenario must run under examples/run.sh")

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
                // Uses the `\${VAR}` form documented above, not `${'$'}VAR`,
                // which bash rejects.
                sh("test -n \"\${API_KEY}\" && test -n \"\${DB_USER}\" && test -n \"\${SSH_KEY_FILE}\" && test -n \"\${SECRET_FILE}\" && test -n \"\${KEYSTORE_PATH}\" && test -n \"\${ZIP_PATH}\" && test -n \"\${U_P}\"")
                echo("7 credential binding kinds resolved")
            }
        }

        stage("credentials-door") {
            // The door, from the outside. These are the product's own verbs,
            // asked the questions an operator asks: is there anything here,
            // what happens if my passphrase is wrong, and what happens if I
            // ask to remove something that was never there.
            //
            // The launcher writes the product's entry point for this scenario,
            // and it carries this scenario's clock: a verb that hangs stops
            // with the scenario instead of hanging the suite.
            sh(
                """
                mkdir -p empty-store
                fresh="${'$'}PWD/empty-store/creds.bin"

                # 1. There is no store here at all. The operator has no
                #    passphrase, and the honest answer is "there is nothing
                #    stored" -- not a request for a key to a door that does
                #    not exist.
                out=$(env -u PIPELINE_STORE_PASSPHRASE PIPELINE_CREDENTIALS_STORE="${'$'}fresh" '${product}' credentials list 2>&1); code=${'$'}?
                if [ "${'$'}code" = "0" ]; then
                    echo NO_STORE_NEEDS_NO_KEY
                else
                    echo "listing an absent store answered ${'$'}code instead of 0:" >&2
                    echo "${'$'}out" >&2
                    echo LEG_no_store_needs_no_key
                fi

                # 2. And looking must not leave a store behind. A verb that
                #    only reads has nothing to create.
                if [ ! -e "${'$'}fresh" ]; then
                    echo NO_STORE_LEAVES_NO_FILE
                else
                    echo "listing an absent store created it" >&2
                    echo LEG_no_store_leaves_no_file
                fi

                # 3. The store IS here and no passphrase comes with it. That
                #    is its own answer, with its own code, and it is not the
                #    answer for a passphrase that does not match.
                out=$(env -u PIPELINE_STORE_PASSPHRASE '${product}' credentials list 2>&1); code=${'$'}?
                if [ "${'$'}code" = "2" ]; then
                    echo NO_PASSPHRASE_IS_ITS_OWN_CODE
                else
                    echo "a store with no passphrase answered ${'$'}code instead of 2:" >&2
                    echo "${'$'}out" >&2
                    echo LEG_no_passphrase_is_its_own_code
                fi

                # 4. The passphrase is wrong. The store must come out of it
                #    exactly as it went in: same bytes, credential alive.
                #    A passphrase that does not match is not allowed to
                #    change anything, and saying "removed" while it does is
                #    the worst kind of answer there is.
                before=$(cksum < "${'$'}PIPELINE_CREDENTIALS_STORE")
                out=$(PIPELINE_STORE_PASSPHRASE=wrong-key '${product}' credentials remove string-creds 2>&1); code=${'$'}?
                after=$(cksum < "${'$'}PIPELINE_CREDENTIALS_STORE")
                if [ "${'$'}code" = "3" ] && [ "${'$'}before" = "${'$'}after" ]; then
                    echo WRONG_KEY_CHANGES_NOTHING
                else
                    echo "removing with a wrong passphrase answered ${'$'}code and left the store ${'$'}before -> ${'$'}after:" >&2
                    echo "${'$'}out" >&2
                    echo LEG_wrong_key_changes_nothing
                fi

                # 5. And it says WHICH answer it is. "Passphrase required"
                #    would send the operator looking for a variable they
                #    already set.
                out=$(PIPELINE_STORE_PASSPHRASE=wrong-key '${product}' credentials list 2>&1); code=${'$'}?
                if [ "${'$'}code" = "3" ] && echo "${'$'}out" | grep -q 'does not match'; then
                    echo WRONG_KEY_IS_NAMED
                else
                    echo "a wrong passphrase answered ${'$'}code and said:" >&2
                    echo "${'$'}out" >&2
                    echo LEG_wrong_key_is_named
                fi

                # 6. Removing what was never there says so, instead of
                #    reporting a removal that did not happen.
                out=$('${product}' credentials remove no-such-credential 2>&1); code=${'$'}?
                if [ "${'$'}code" = "1" ] && echo "${'$'}out" | grep -q 'No credential named'; then
                    echo MISSING_CREDENTIAL_IS_NAMED
                else
                    echo "removing an absent credential answered ${'$'}code and said:" >&2
                    echo "${'$'}out" >&2
                    echo LEG_missing_credential_is_named
                fi
                """.trimIndent(),
            )
        }
    }
}
