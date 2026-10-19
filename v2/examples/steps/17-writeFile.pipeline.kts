pipeline {
    stages {
        stage("writefile-step") {
            writeFile(file = "out.txt", text = "hello")
            sh("cat out.txt")
        }
    }
}
