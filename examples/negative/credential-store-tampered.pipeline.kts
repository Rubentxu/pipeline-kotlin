//@ credentials door-creds secret-text
//@ credentials-refusal tampered-store
//@ expect-exit 4
//@ expect-stdout Error: store tampered
// El sujeto: un store de credenciales que NO pasa su propia comprobación de
// integridad tiene su propia respuesta, distinta de la de un store que está
// cerrado y una clave que no es la.
//
// Este escenario hace las dos cosas. Primero: siembra un store con la
// distribución propia y ENLAZA esa credencial, así que la corrida abre el
// store y resuelve el binding. El store está bien. La puerta abre.
//
// Segundo: el lanzador pide a la misma puerta sobre una COPIA de ese mismo
// store con la magic cambiada. La copia es lo que se rompe, y lo que se
// rompe son los bytes que el producto escribió, no bytes inventados por el
// harness. La puerta tiene que decir que el store NO PASÓ su comprobación.
//
// La diferencia que se afirma es entre dos hechos que se parecen mucho y se
// resuelven distinto. "El store está manipulado" se arregla devolviendo el
// store de su copia de seguridad; "la clave no es la" se arregla recuperando
// la clave. Un producto que respondiera a los dos con el mismo mensaje
// dejaría al operador reparando la cosa equivocada, y un producto que
// respondiera a los dos con el mismo CÓDIGO dejaría a un script sin poder
// distinguirlos.
//
// El código esperado es el 4, y no un 1 ni un 2: la corrida no falló, el
// pedido tampoco fue mal formado, y la respuesta es sobre el estado del
// material que se pidió.
//
// El lanzador nombra la línea que no llegó cuando la puerta responde otra
// cosa, así que una garantía rota dice cuál fue.

// Shell variable expansion inside a string. The correct Kotlin escape is
// `\${VAR}` (a single backslash before `$`, then the braced identifier). The
// form `\${'$'}VAR` looks similar but is a TRAP: Kotlin compiles `${'$'}` to a
// literal `$`, then `VAR` is adjacent text, and bash receives `${'$'}VAR` which
// it rejects as `sustitución errónea`.

pipeline {
    stages {
        stage("the-store-that-opens") {
            withCredentials(listOf(
                StepSpec.CredentialsBinding.string(
                    "door-creds",
                    "API_KEY"
                )
            )) {
                sh("test -n \"\${API_KEY}\" && echo THE_INTACT_STORE_OPENED")
            }
        }
    }
}
