// S2-A6/G3R — `core.pwd.tmp` installed-CLI evidence (fresh + replay).
//
// Exercises the registry-path `core.pwd.tmp` Step through the DSL surface
// (pipeline { pwd(tmp = true) }). The Step's effect emits a single `PwdResolved`
// typed domain event carrying `path: String` (the deterministic tmp workspace).
// The echo below proves the synchronous DSL return value still works (legacy
// PATH A: runtimeConfig.userDir() placeholder); the real tmp directory is
// created by the adapter (sha256(opId.format()) under workspaceRoot).
//
// Failures here mean one of:
//   - registry resolution broke (StepKey "core.pwd.tmp" not found)
//   - the registry handler regressed (no PwdResolved, or wrong path shape)
//   - the DSL lowering reverted to legacy StepSpec.Pwd(tmp=true)
//
// G3R scope firewall:
//   - `pwd()` and `pwd(tmp=false)` STILL lower to legacy `core.pwd` (the
//     `core.pwd` StepKey is still LEGACY until G4 flips authority).
//   - `pwd(tmp=true)` alone lowers to `core.pwd.tmp` (Registry).
pipeline {
    stages {
        stage("pwd-tmp-step") {
            val dir = pwd(tmp = true)
            echo("pwd(tmp=true) synchronous return: " + dir)
            // A second invocation must produce a DIFFERENT path at runtime
            // (different stepIndex ⇒ different OpId ⇒ different sha256).
            val dir2 = pwd(tmp = true)
            echo("pwd(tmp=true) second invocation: " + dir2)
            // DO NOT assert `dir != dir2` here. It was written, and it is
            // wrong: both values are the literal string `<workspace>`.
            // `StageScope.pwd` records the step and returns the
            // `runtimeConfig.userDir()` placeholder -- its KDoc states the
            // placeholder "MUST NOT be used as if it were the real runtime
            // path" and that scripts needing the real value must route through
            // the scripted runtime context. So the distinctness invariant
            // above is REAL but not observable through this return value, and
            // asserting on it manufactures a defect that does not exist.
            //
            // The real path arrives as the step's output event. Reaching it
            // needs a third launcher directive (`//@ expect-stdout`), which is
            // the open product decision recorded in ROADMAP.md, not something
            // a scenario may grow on its own.
            // And the legacy `pwd()` must still work — proves core.pwd not
            // accidentally re-routed.
            val legacy = pwd()
            echo("pwd() legacy return: " + legacy)
        }
    }
}
