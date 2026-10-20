pipeline {
    stages {
        stage("writefile-step") {
            writeFile(file = "out.txt", text = "hello")
            // `cat` prints whatever is there and exits 0 whether the file
            // exists, is empty, or holds something else entirely -- so the
            // step's subject was never actually observed. `-qx` is line-exact
            // rather than a substring match, so a file containing
            // "hello world" does not satisfy a guarantee about "hello".
            sh("grep -qx hello out.txt")
        }
    }
}
