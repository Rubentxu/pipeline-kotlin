pipeline {
    stages {
        stage("checkout") {
            sh("cd /tmp && rm -rf smoke-repo && git init -q smoke-repo && cd smoke-repo && echo smoke > README.md && git add . && git -c user.email=s@l -c user.name=s commit -qm init")
            sh("git clone /tmp/smoke-repo .")
        }
        stage("build") {
            sh("mkdir -p build/libs && echo jar > build/libs/smoke.jar")
        }
        stage("archive") {
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
