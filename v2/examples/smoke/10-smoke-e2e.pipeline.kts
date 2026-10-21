//@ expect-stdout smoke-e2e-readme
val home = System.getenv("PIPELINEK_E2E_HOME") ?: System.getProperty("user.dir")
val fixture = "$home/smoke-repo"
pipeline {
    stages {
        stage("checkout") {
            sh("rm -rf '$fixture' && git init -q '$fixture' && cd '$fixture' && echo smoke-e2e-readme > README.md && git add . && git -c user.email=s@l -c user.name=s commit -qm init")
            sh("git clone '$fixture' .")
            sh("cat README.md")
        }
        stage("build") {
            sh("mkdir -p build/libs && echo jar > build/libs/smoke.jar")
            archiveArtifacts(artifacts = "build/libs/*.jar", allowEmptyArchive = false)
        }
    }
}
