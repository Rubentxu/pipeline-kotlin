//@ expect-outcome unstable
//@ expect-stdout fichero-avisado

// THE GUARANTEE UNDER TRIAL
//
// The other half of the same capability: a `writeFile` declared inside a
// `warnError` writes the file, with its declared content and encoding.
//
// It used to be TEXT. A control scope is projected to a shell wrapper, and the
// `writeFile` was rendered into that text as the literal
// `writeFile('fichero','texto','UTF-8')`. The shell has no such command, so the
// run died with a shell syntax error, the file was never created, and the run
// concluded UNSTABLE. The absence was the severe half: a file that was never
// written and a file nobody asked for look the same from outside.
//
// The witness is the file ITSELF, read back by its BYTES. `od` prints the
// encoding of the declared text, so a file written in another encoding, and a
// file that was never written, both fail this scenario.
//
// The ORDER is witnessed too, and it is an ORDER and not a membership. The
// first shell of the scope leaves the file with the bytes `antes`; the
// `writeFile` OVERWRITES that same file with the declared text; the last shell
// reads the bytes back. It sees `café` only if the `writeFile` ran AFTER the
// first shell, and it sees a file at all only if it ran BEFORE the last one.
//
// WHY THE RUN CONCLUDES UNSTABLE HERE, AND THAT IS NOT A DEFECT
//
// A `warnError` publishes an AVISO, and an aviso degrades the stage: the run
// concludes UNSTABLE even though every step inside the scope succeeded. This
// is the product's own definition of an aviso, measured on the built
// distribution, and the scenario states it rather than hiding it. A scenario
// that expected `success` here would be asserting a conclusion the product
// does not publish -- and the guarantee under trial is NOT the conclusion, it
// is the FILE, which the last shell reads back by its bytes.
//
// The command avoids single quotes on purpose. The scope wrapper escapes them
// for a quoted context that its own script is not, so a single quote inside a
// command of a control scope reaches the shell already broken. That is a
// separate defect from the one under trial, and this scenario does not need to
// depend on it.
pipeline {
    stages {
        stage("writefile-en-warnerror") {
            warnError(message = "aviso sin fallo") {
                sh("printf antes > avisado.txt")
                writeFile(file = "avisado.txt", text = "café", encoding = "UTF-8")
                sh("od -An -tx1 -N5 avisado.txt | tr -d \" \\n\" | grep -qx 636166c3a9 && echo fichero-avisado")
            }
        }
    }
}
