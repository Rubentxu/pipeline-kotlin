pipeline {
    stages {
        stage("fileops") {
            writeFile(file = "test.txt", text = "hello world")
            def content = readFile(file = "test.txt")
            echo("read: ${content}")
        }
    }
}
