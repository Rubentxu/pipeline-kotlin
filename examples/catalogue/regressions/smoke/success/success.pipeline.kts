// Fixture: real Python unittest with test report + output marker in workspace.
// Do not use unsupported steps { } wrapper in a PipelineK version that rejects it.
pipeline {
    stages {
        stage("python-unit") {
            sh("mkdir -p out && python3 -m unittest discover -s tests -v > out/tests.log 2>&1 && printf 'ok\\n' > out/test-ok.txt")
        }
    }
}
