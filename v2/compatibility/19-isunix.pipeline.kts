// S2-A5/G8 — core.isUnix installed-CLI evidence (fresh + replay).
//
// Exercises the registry-path `core.isUnix` Step through the DSL surface
// (pipeline { isUnix() }). The Step's effect emits a single `UnixDetected`
// typed domain event carrying `isUnix: Boolean`; the echo below proves the
// boolean reaches the script as a typed value (compatible with Jenkins).
//
// Failures here mean one of:
//   - registry resolution broke (StepKey "core.isUnix" not found)
//   - the registry handler regressed (no UnixDetected, or wrong boolean)
//   - the legacy compiler still routes via a removed path
pipeline {
    stages {
        stage("isUnix-step") {
            val unix = isUnix()
            if (unix) {
                echo("is unix: true")
            } else {
                echo("is unix: false")
            }
        }
    }
}
