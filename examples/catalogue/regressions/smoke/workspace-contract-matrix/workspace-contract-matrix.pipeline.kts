// Regresion externa del defecto "workspace contract" en PipelineK.
// Sintomas reportados antes de la rc1:
//   sh() cwd no coincide con dir() block, o dir() no persiste cwd entre etapas.
//
// Esta regresion cubre la matriz:
//   - dir() anidado (3 niveles)
//   - writeFile via sh() con printf
//   - readFile via sh() con cat
//   - fileExists via sh() con test -f
//   - read en etapa DISTINTA (persistencia cross-stage)
//   - rama paralela (branch() izq/der)
//
// Un escenario solo es PASS cuando el oraculo externo ha comprobado:
//   1) cwd efectivo == /workspace/<dir> tras dir()
//   2) el archivo escrito en dir() es visible desde fuera (persistencia)
//   3) read en otra etapa retorna el contenido escrito (writeFile/readFile)
//   4) la rama paralela escribe su propio archivo sin pisar a la otra
//   5) re-entrar dir() desde la raiz funciona (cwd coherente)
//
// NOTA: el caso negativo "dir() con step que falla deberia restaurar cwd"
// NO se incluye porque pipeline-kotlin aborta el run al primer step fallido
// (RunFinished outcome=failure); ese comportamiento es valido por si mismo.
// La restauracion de cwd tras dir() exitoso se valida via evento DirExited
// en el journal (logs/pipelinek.log), fuera de este oraculo.
//
// Ejecutar contra versiones:
//   - 0.39.0       -> este escenario distingue si cwd es coherente
//   - 0.39.1-rc1   -> esperado EXECUTED_PASS
//   - 0.39.1-rc2   -> esperado EXECUTED_PASS
//
// El oraculo final (certify --all) exige exit=0 + outcome=success + 5 archivos
// de marcador en out/. Si falta alguno, EXECUTED_FAIL.
pipeline {
    stages {
        stage("dir-nested-3") {
            dir("a/b/c") {
                sh("mkdir -p /workspace/out && pwd > /workspace/out/wu-rp-053-cwd-a-b-c.txt && grep -q '/workspace/a/b/c' /workspace/out/wu-rp-053-cwd-a-b-c.txt && printf 'one\\ntwo\\nthree\\n' > file.txt && test -f file.txt && cat file.txt | wc -l > /workspace/out/wu-rp-053-lines.txt && test \"$(cat /workspace/out/wu-rp-053-lines.txt)\" = \"3\"")
            }
        }
        stage("read-cross-stage") {
            sh("cat /workspace/a/b/c/file.txt > /workspace/out/wu-rp-053-readback.txt && grep -qx one /workspace/out/wu-rp-053-readback.txt")
        }
        stage("parallel-branches") {
            parallel {
                branch("left") {
                    sh("mkdir -p /workspace/out && printf 'left-branch-payload\\n' > /workspace/out/wu-rp-053-branch-left.txt")
                }
                branch("right") {
                    sh("mkdir -p /workspace/out && printf 'right-branch-payload\\n' > /workspace/out/wu-rp-053-branch-right.txt")
                }
            }
        }
        stage("dir-reattach") {
            // Tras las etapas anteriores, cwd deberia estar en /workspace.
            // Re-entrar a dir() debe seguir dando cwd coherente.
            dir("x/y") {
                sh("pwd > /workspace/out/wu-rp-053-cwd-x-y.txt && grep -q '/workspace/x/y' /workspace/out/wu-rp-053-cwd-x-y.txt")
            }
        }
    }
}
