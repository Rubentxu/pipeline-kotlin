import example.uppercase.uppercaseObserved

// ENTREGA B fixture: a real pipeline that uses ONLY the external plugin's contribution.
//
// `uppercaseObserved` is the plugin's observing Step. It is the right subject for "the plugin
// executed" rather than "the file compiled", because its handler does two things in one run: it
// uppercases the input, and it emits the plugin's OWN event kind through the host's emission seam.
// The event therefore exists only if the handler actually ran, on a composition that actually
// admitted the plugin — a script that merely parsed would produce neither.
//
// The plain `uppercase` Step is deliberately not used here: it demands nothing from the host, so a
// run of it is a weaker witness. `uppercaseObserved` requires exactly one capability, and getting
// the event out proves the host supplied it to a handler that ran.
pipeline {
    stages {
        stage("External") {
            uppercaseObserved("hello")
        }
    }
}
