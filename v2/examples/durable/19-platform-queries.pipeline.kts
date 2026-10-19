//@ expect-stdout is unix: true
//@ expect-stdout pwd(tmp=true) is the workspace placeholder: true
//@ expect-stdout pwd is the workspace placeholder: true

// Two platform queries in one scenario: the boolean the platform reports, and
// the directory it hands out.
// The boolean is observable twice: in what the pipeline printed, and in the
// bytes the run COMMITTED to the Output Plane. A handler that returned one
// boolean and printed another would pass an exit-code check and fail this one.
// The tmp directory is observable only as a placeholder. The script's return
// value is the workspace placeholder, which is the documented contract and
// not the real path, so asserting on it asserts that a placeholder is a
// placeholder. The real path the adapter created travels as a typed event,
// and a scenario can only reach it once the event stream is readable from
// outside; the durable transcript is the surface this scenario can assert on
// today, and it says the placeholder is what came back.
pipeline {
    stages {
        stage("platform-queries") {
            val unix = isUnix()
            if (unix) {
                echo("is unix: true")
            } else {
                echo("is unix: false")
            }

            // The script's return value is the workspace placeholder, and the
            // contract says it stays that way: the real path travels as a
            // typed event, and a script that needs it must ask for the event.
            // Asserting the placeholder is therefore a real assertion, not a
            // tautology: a change that made pwd hand back the real path
            // would break it, and that change would break the contract.
            val dir = pwd(tmp = true)
            echo("pwd(tmp=true) is the workspace placeholder: " + (dir == "<workspace>"))

            // The legacy call must still work: a change that routed every
            // pwd through the tmp variant would be silent otherwise.
            val legacy = pwd()
            echo("pwd() is the workspace placeholder: " + (legacy == "<workspace>"))
        }
    }
}
