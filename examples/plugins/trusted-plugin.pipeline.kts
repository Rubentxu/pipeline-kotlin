//@ expect-stderr Discovered external Step plugins: example.uppercase
//@ expect-stderr admitted under the operator's trust policy
//@ expect-stdout registrystep-0 finished
//@ plugin-jar bundled-uppercase
//@ plugin-trust signed

// The subject is the operator's trust policy over a plugin they bring.
//
// The launcher builds the operator's material for this scenario: a publisher key pair, the
// detached signature over the plugin's bytes, and the row that pins those bytes and names the
// publisher and the key. The runtime is pointed at that row with --plugin-trust, so the plugin
// the operator brings is admitted only because it is the artifact they approved.
//
// What is asserted here is that a policy in force ADMITS a plugin that is approved, and says so.
// The report line is the evidence that the policy was consulted at all: a run that admitted the
// plugin for some other reason — because the manifest was fine, because the API range matched —
// would produce the same steps and the same exit code, and only the report distinguishes them.
// A policy that refused everything would also "refuse correctly" and would leave an operator
// unable to use the plugins they vetted, so this scenario is the half of the guarantee that a
// refusal-only test cannot see.
//
// The other half is the negative scenario beside it: the same plugin, with its bytes changed
// after the publisher signed them, is refused by name.
import example.uppercase.uppercase

pipeline {
    stages {
        stage("External") {
            uppercase("hello")
        }
    }
}
