pipeline {
    stages {
        stage("cw-g7-01") {
            sh("mkdir -p test/sub && echo alpha > test/a.txt && echo beta > test/b.txt && echo gamma > test/sub/c.txt && echo keep > keep.txt")
            cleanWs(patterns = listOf("test/**"))
        }
    }
}
