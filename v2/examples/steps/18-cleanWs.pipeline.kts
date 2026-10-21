//@ expect-stdout workspace-cleaned
pipeline {
    stages {
        stage("cleanws-step") {
            sh("mkdir -p test && touch test/a.txt test/b.txt")
            cleanWs(patterns = listOf("test/**"))
            sh("test ! -f test/a.txt && echo workspace-cleaned")
        }
    }
}
