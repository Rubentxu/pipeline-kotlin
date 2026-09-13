pipeline {
    stages {
        stage("cw-g7-03") {
            sh("mkdir -p target/inner && echo r1 > target/a.txt && echo r2 > target/b.txt && echo r3 > target/inner/c.txt")
            cleanWs()  // deleteDirs=true, patterns=null/empty => delete all non-.v2
        }
    }
}
