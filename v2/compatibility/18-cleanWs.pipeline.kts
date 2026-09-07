pipeline {
    stages {
        stage("cleanws-step") {
            sh("mkdir -p test && touch test/a.txt test/b.txt")
            cleanWs(patterns = listOf("test/**"))
        }
    }
}
