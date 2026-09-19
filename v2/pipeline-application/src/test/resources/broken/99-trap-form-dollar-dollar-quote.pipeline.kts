// SH-VAR-SCOPE.S2 negative fixture.
// Documents the trap form `${'$'}VAR` as a NEGATIVE example.
// Kotlin compiles `${'$'}` (a string template evaluating to `$`) and then
// `VAR` (literal text), producing the bytes `${'$'}VAR` (10 chars).
// bash receives that and rejects it with `sustitución errónea`.
// See CHARACTERISATION.md §5.3 for the byte-level proof.
//
// This fixture exists to LOCK the trap form into test coverage so a future
// refactor of the comment on 14-credentials-bindings.pipeline.kts:7 cannot
// silently regress by re-citing the trap form. Run this fixture under the
// "broken" corpus: it must FAIL with exit code 1 and stderr containing
// "sustitución errónea".

pipeline {
    stages {
        stage("trap-form") {
            // TRAP: hand-written `${'$'}VAR` produces invalid bash.
            sh("echo trap=\${'\$'}USER")
        }
    }
}
