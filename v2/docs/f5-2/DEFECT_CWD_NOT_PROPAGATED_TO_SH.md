# DEFECT: `dir(...)` block does not propagate cwd to `core.sh`

## Status
OPEN — F5.2 does not depend on it; reproduced and documented.

## Symptom
A scenario using `dir("hello-world") { sh("./gradlew test") }` fails with
exit code 127 ("file not found") because `sh` runs in the workspace root,
not in `hello-world/`.

## Reproducer
```kotlin
// scenario.kts
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.scmGitCheckout
pipeline {
    stages {
        stage("checkout") {
            scmGitCheckout(
                url = "file:///tmp/pk-uat-f5-2-fixture/git/fixture.git",
                branch = "master",
                poll = false,
                changelog = false,
                relativeTargetDir = "hello-world",
            )
        }
        stage("test") {
            dir("hello-world") {
                sh("./gradlew test")
            }
        }
    }
}
```

## Observed
- `DirEntered` event is emitted with `path = /tmp/pk-uat-f5-2/ws2/hello-world` and `previousPath = /tmp/pk-uat-f5-2/ws2` (the `dir` block DOES track the cwd correctly).
- `DirExited` event is emitted after the body finishes.
- `StepFailed` sh: `script.sh: línea 1: ./gradlew: No existe el fichero o el directorio` — the shell runs from `controlDir`, NOT from `hello-world/`.
- The workspace dir IS correctly populated (`ls /tmp/pk-uat-f5-2/ws2/hello-world` shows `build.gradle.kts`, `src/`, etc.) — the problem is exclusively that `core.sh` does not consume the cwd from the `dir` overlay.

## Root cause (preliminary)
`CanonicalDurableRunCoordinator.kt:974` passes `stageShOptions` (without
`workingDirectory`) to the nested `core.sh` runtime context, ignoring the
`childShOptions` derived from the `dir(...)` block scope at line 1666.

The `dir` block correctly pushes `ContextOverlay.Cwd(target)` (line 1655)
and correctly emits `DirEntered` events; the seam through the runtime
context is broken.

## Workaround (until fixed)
Use absolute paths in `sh`, or invoke `core.sh` after `core.pwd.tmp`
that writes the resolved workspace path.

## Decision
Reproduced and documented. NOT fixed in F5.2 — the F5.2 gate does NOT
require cwd propagation. Filed as a pre-existing defect for the core
`dir`/`sh` integration, separate from F5.2.
