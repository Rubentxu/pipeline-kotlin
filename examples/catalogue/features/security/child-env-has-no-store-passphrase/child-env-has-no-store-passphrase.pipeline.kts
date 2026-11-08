// The subject is the boundary between the controller's authority and the
// child's environment.
//
// The passphrase that opens the credential store belongs to the CONTROLLER: it
// resolved it to open a store that holds every secret the machine keeps. A
// step that inherited it would hold the key to all of them, which is more
// authority than the pipeline asked for and more than any single binding
// grants. The pipeline did not ask for it, so the pipeline must not get it.
//
// The store's LOCATION is not the passphrase: it says where the store is, and
// on its own it grants nothing. Filtering it would break the flows that point
// the product at a store without opening it, so it crosses.
//
// And what the pipeline DOES ask for crosses: a credential the pipeline
// declares on purpose is the channel that delivers it, and the filter is not
// allowed to touch that channel.
//
// This scenario runs inside the controller, so it can read the controller's own
// environment and know the passphrase without anybody typing it. That is what
// makes the third check possible: the passphrase is declared ON PURPOSE, it
// reaches the child, and the committed transcript shows it masked. A product
// that printed it in clear would be failing the guarantee that a credential
// never appears in an output in clear.
//
// Each check prints its own marker and the launcher names the marker that did
// not arrive, so a broken guarantee says which one it was.

// Shell variable expansion inside a string. The correct Kotlin escape is
// `\${VAR}` (a single backslash before `$`, then the braced identifier). The
// form `\${'$'}VAR` looks similar but is a TRAP: Kotlin compiles `${'$'}` to a
// literal `$`, then `VAR` is adjacent text, and bash receives `${'$'}VAR` which
// it rejects as `sustitución errónea`.

// The controller's own passphrase. It is READ, never printed, and never written
// into a file by this scenario: the value only travels as an environment
// variable that the pipeline declares on purpose.
val controllerPassphrase = System.getenv("PIPELINE_STORE_PASSPHRASE")
    ?: error(
        "PIPELINE_STORE_PASSPHRASE is not set: this scenario needs a controller that has a store",
    )

pipeline {
    stages {
        // 1. The precondition. The store is opened only when the run binds a
        //    credential, so the passphrase is resolved and registered only when
        //    this stage binds one. Without this check the third stage would
        //    assert redaction of a value nobody had registered.
        stage("the-store-opened") {
            withCredentials(listOf(
                StepSpec.CredentialsBinding.string(
                    "store-witness",
                    "WITNESS"
                ),
            )) {
                sh("test -n \"\${WITNESS}\" && echo THE_STORE_OPENED_FOR_THE_BINDING")
            }
        }

        // 2. The child's environment, as the shell sees it. The passphrase the
        //    controller holds must NOT be among the inherited variables.
        stage("the-inherited-env") {
            sh(
                """
                out=$(sh -c env)
                if printf '%s\n' "${'$'}out" | grep -q '^PIPELINE_STORE_PASSPHRASE='; then
                    echo PASSPHRASE_LEAKED_INTO_THE_CHILD_ENV
                else
                    echo PASSPHRASE_NOT_IN_THE_CHILD_ENV
                fi

                # The locator is not the credential. It says WHERE the store is,
                # and without the passphrase it says nothing anybody can use, so
                # it crosses.
                if printf '%s\n' "${'$'}out" | grep -q '^PIPELINE_CREDENTIALS_STORE='; then
                    echo THE_STORE_LOCATOR_REACHED_THE_CHILD
                else
                    echo THE_STORE_LOCATOR_DID_NOT_REACH_THE_CHILD
                fi
                """.trimIndent(),
            )
        }

        // 3. The request channel. What the pipeline declares on purpose is
        //    delivered: the filter removes the INHERITANCE, not the request.
        stage("the-declared-env") {
            environment {
                env("PIPELINE_STORE_PASSPHRASE", controllerPassphrase)
            }
            sh(
                """
                if [ -n "${'$'}PIPELINE_STORE_PASSPHRASE" ]; then
                    echo THE_DECLARED_PASSPHRASE_REACHED_THE_CHILD
                else
                    echo THE_DECLARED_PASSPHRASE_DID_NOT_REACH_THE_CHILD
                fi
                """.trimIndent(),
            )
        }

        // 4. And a credential that reaches an output is MASKED there. The
        //    committed transcript is what the run says and what it keeps; the
        //    value that arrives in clear would be the leak this whole scenario
        //    is about, one channel further down.
        stage("the-redaction") {
            environment {
                env("PIPELINE_STORE_PASSPHRASE", controllerPassphrase)
            }
            sh("echo \"THE_DECLARED_PASSPHRASE_VALUE=\${PIPELINE_STORE_PASSPHRASE}\"")
        }
    }
}
