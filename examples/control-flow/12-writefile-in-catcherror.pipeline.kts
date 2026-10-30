//@ expect-outcome success
//@ expect-stdout fichero-capturado

// THE GUARANTEE UNDER TRIAL
//
// A `writeFile` declared inside a control block is the SAME capability as a
// `writeFile` declared at stage level: it writes the file.
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
// file that was never written, both fail this scenario. A `grep` over the text
// would only prove that some line matched.
//
// The ORDER is witnessed too, and it is an ORDER and not a membership. The
// first shell of the scope leaves the file with the bytes `antes`; the
// `writeFile` OVERWRITES that same file with the declared text; the last shell
// reads the bytes back. It sees `café` only if the `writeFile` ran AFTER the
// first shell, and it sees a file at all only if it ran BEFORE the last one.
// A scope that ran its steps in another order, or that skipped one, cannot
// produce that observation.
//
// The command avoids single quotes on purpose. The scope wrapper escapes them
// for a quoted context that its own script is not, so a single quote inside a
// command of a control scope reaches the shell already broken. That is a
// separate defect from the one under trial, and this scenario does not need to
// depend on it.
//
// This stage ends in SUCCESS, and that is the point of the choice of scope: a
// `catchError` that catches nothing leaves the run stable, so the guarantee
// under trial is observable without the instability that an AVISO adds by
// design. The `warnError` half of the same capability lives in the sibling
// scenario, where the run's published conclusion is `unstable` because that is
// what an AVISO means in this product.
pipeline {
    stages {
        stage("writefile-en-catcherror") {
            catchError {
                sh("printf antes > capturado.txt")
                writeFile(file = "capturado.txt", text = "café", encoding = "UTF-8")
                sh("od -An -tx1 -N5 capturado.txt | tr -d \" \\n\" | grep -qx 636166c3a9 && echo fichero-capturado")
            }
        }
    }
}
