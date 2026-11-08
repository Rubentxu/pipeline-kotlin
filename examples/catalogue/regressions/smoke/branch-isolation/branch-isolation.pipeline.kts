// Ramas contextuales aisladas + side effects caracterizados.
// parallel con dos branch(): ambos se ejecutan (aislados, identidad
// b0:/b1: en journal) y cada uno deja su side effect marcado en out/.
pipeline {
    stages {
        stage("branch-iso") {
            parallel {
                branch("left") {
                    sh("mkdir -p /workspace/out && printf 'left-taken\\n' > /workspace/out/branch-left.txt")
                }
                branch("right") {
                    sh("mkdir -p /workspace/out && printf 'right-taken\\n' > /workspace/out/branch-right.txt")
                }
            }
        }
    }
}
