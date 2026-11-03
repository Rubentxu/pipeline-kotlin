//@ plugin-jar missing
//@ expect-exit 3
//@ expect-stderr Passphrase required
//@ expect-stderr Hint: set PIPELINE_STORE_PASSPHRASE
// El sujeto: cuando DOS cosas están mal a la vez, la corrida dice UNA sola
// cosa, y dice lo mismo con journal que sin journal.
//
// Este escenario junta los dos rechazos que una corrida puede encontrarse:
// una credencial enlazada cuyo store está cerrado (no hay passphrase a mano)
// y un artefacto de plugin que el runtime no puede medir porque el fichero
// que el operador nombró no está. Los dos son rechazos legítimos y los dos
// tienen su código, así que la pregunta no es cuál de los dos gana: es que
// la respuesta NO dependa de si la corrida lleva journal.
//
// La garantía es que el mismo Hecho produce el mismo código en los dos
// mundos. Con el journal, la respuesta era 3; sin journal, la respuesta era
// 2, porque el rechazo del plugin se componía antes que el de la
// credencial. Un operador que compara el código de salida de una corrida
// durable con el de una efímera recibía dos respuestas para una situación, y
// un script que decide con el código no podía decidir.
//
// Por eso el código esperado es el 3 y no un 2: lo que este escenario afirma
// no es que el plugin se pueda cargar, sino que la CORRIDA dice por qué no
// puede seguir, y lo dice igual en los dos mundos. El artefacto ilegible se
// cuenta aparte, cuando el operador lo nombre.
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
        stage("two-things-wrong-at-once") {
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
