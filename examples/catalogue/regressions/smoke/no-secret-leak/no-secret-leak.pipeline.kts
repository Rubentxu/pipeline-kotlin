// Regresion de no-filtracion de secretos en logs de pipeline-kotlin.
// El canario emite contenido "normal" (mensajes de compilacion, paths,
// resultados de tests) y exige que el detector de secretos (harness/secrets.py)
// NO encuentre ningun patron reconocible. Si PASARA con hallazgos, seria un
// DEFECTO DE SEGURIDAD: el log esta filtrando un secreto.
//
// Limites honestos:
//   - La certificacion NO monta SecretProvider, asi que este canario no prueba nada
//     contra un vault real; prueba que el shell NO filtra patrones tipicos
//     (AWS keys, GitHub PATs, JWTs, PEM, generic password=) en su salida.
//   - El detector vive en el arnes de releasing y se invoca desde sus tests
//     (no desde el DSL).
//
// Ejecutar contra versiones:
//   - 0.39.0       -> EXECUTED_PASS (no se emite contenido sensible)
//   - 0.39.1-rc1   -> EXECUTED_PASS
//   - 0.39.1-rc2   -> EXECUTED_PASS
//
pipeline {
    stages {
        stage("clean-build") {
            sh("echo 'compiling rust-canary v0.1.0'")
            sh("echo 'all 2 tests passed'")
            sh("ls -la /workspace | head -3")
        }
    }
}
