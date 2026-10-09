// W6.2b fixture: an EXTERNAL directive, executed by the installed distribution.
//
// Shape is the canonical one from StageScope.directives (pipeline-scripting-api, StageScope.kt
// ~line 725): the directive is DECLARED in a `directives { }` block and the stage's steps are its
// siblings. `directive` is not a lambda wrapper -- an earlier draft of this fixture used it as one
// and the compiler rejected it with `Unresolved reference 'directive'` at the use site, which is
// the fail-closed behaviour the contract promises.
//
// `acme.lock` is owned by the plugin: the host resolves the key only because the JAR contributes
// it. The witness is the STAGE BODY. A stage directive composes a scope around the stage's steps,
// so "the directive was admitted" and "the body ran under it" are separate claims, and only the
// second one proves composition happened.
//
// The doc comment on `directives` states the negative this fixture exists to complete: a script
// declaring a directive against an engine that registers none FAILS with a typed USER failure
// naming the unresolved key -- never a silent skip. So the isolation pair (same script, no JAR)
// is the stronger half of this evidence, not a duplicate.
pipeline {
    stages {
        stage("locked") {
            directives {
                directive("acme.lock", """{"resource":"prod-db"}""")
            }
            echo("body under external directive ran")
        }
    }
}
