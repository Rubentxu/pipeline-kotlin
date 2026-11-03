//@ expect-exit 3
//@ expect-stderr Passphrase required
//@ expect-stderr Hint: set PIPELINE_STORE_PASSPHRASE
// El sujeto: una corrida que ENLAZA una credencial no puede abrir el store
// cuando la passphrase que lo abre no está a mano, y lo dice con SU propio
// código en vez de fingir que la credencial iba vacía.
//
// Este escenario no declara `//@ credentials`, así que el lanzador le deja la
// máquina SIN passphrase y le copia el store cifrado de la etapa. Esa es la
// situación real de un operador que tiene credenciales en el store y no ha
// dicho con qué clave abrirlo.
//
// Lo que se afirma es que el fallo tiene NOMBRE y tiene CÓDIGO. Un pipeline
// que se traga el error y sigue enlazando una credencial vacía cumpliría un
// `expect-exit 0` y dejaría al operador con un store que él cree abierto y
// un binding que no vale nada. Un pipeline que revienta con un error genérico
// cumpliría un exit 1 y no distinguiría "no hay clave" de "el run falló".
//
// El código que se espera es el 3, y no un 1 ni un 2: la corrida no falló y
// el pedido tampoco fue mal formado. La puerta se cerró antes de que la
// corrida empezara, y por eso tiene su propio código.
//
// Cada marca se imprime y el lanzador nombra la que no llegó, así que una
// garantía rota dice cuál fue.

// Shell variable expansion inside a string. The correct Kotlin escape is
// `\${VAR}` (a single backslash before `$`, then the braced identifier). The
// form `\${'$'}VAR` looks similar but is a TRAP: Kotlin compiles `${'$'}` to a
// literal `$`, then `VAR` is adjacent text, and bash receives `${'$'}VAR` which
// it rejects as `sustitución errónea`.

pipeline {
    stages {
        stage("the-door-without-a-key") {
            withCredentials(listOf(
                StepSpec.CredentialsBinding.string(
                    "door-creds",
                    "API_KEY"
                )
            )) {
                sh("test -n \"\${API_KEY}\" && echo THE_BINDING_RESOLVED")
            }
        }
    }
}
