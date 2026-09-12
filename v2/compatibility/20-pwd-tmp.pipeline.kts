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
            // And the legacy `pwd()` must still work — proves core.pwd not
            // accidentally re-routed.
            val legacy = pwd()
            echo("pwd() legacy return: " + legacy)
        }
    }
}
