// Regression canary for the workspace contract (the fix shipped in 0.39.1-rc1).
// Verifies that sh() runs with cwd == /workspace (the workspace contract).
pipeline {
    stages {
        stage("workspace-contract") {
            sh("mkdir -p out && (pwd > out/pwd.txt; grep -qx /workspace out/pwd.txt && printf 'cwd-ok\\n' > out/cwd-marker.txt)")
        }
    }
}
