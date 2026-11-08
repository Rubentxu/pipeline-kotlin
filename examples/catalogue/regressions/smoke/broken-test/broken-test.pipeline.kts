// Canary test roto a proposito. El unittest debe fallar (>0 failures), el
// pipeline debe terminar en failure y dejar el XML de resultados fresco.
pipeline {
    stages {
        stage("python-broken") {
            sh("mkdir -p out && python3 -m unittest discover -s tests -v > out/tests.log 2>&1; exit \\$?")
        }
    }
}
