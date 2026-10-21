//@ expect-stdout acquired
// An EXTERNAL directive plugin, discovered through ServiceLoader and admitted
// before the stage runs.
//
// The example-directive-plugin JAR ships inside the distribution, so this
// scenario proves three things that no unit test can: that a plugin
// contributed outside the product's own module list is discovered from the
// runtime classpath, that its BEFORE_STAGE directive is admitted rather than
// silently skipped, and that `Evaluate` policy lets the stage proceed.
//
// The directive itself emits nothing (`DirectiveDefinition<LockInput, Unit>`),
// so the assertion that matters is the stage running at all. An engine that
// failed to discover the contributor would refuse the unresolved key, which is
// what negative/unknown-directive-key.pipeline.kts pins down from the other side.

pipeline {
    stages {
        stage("Acquire lock") {
            directives {
                directive("acme.lock", """{"resource":"prod"}""")
            }
            sh("echo acquired")
        }
    }
}