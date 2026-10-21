//@ expect-stdout keep.txt
pipeline {
    stages {
        stage("build") {
            writeFile(file = "src/keep.txt", text = "keep")
            writeFile(file = "src/drop.pem", text = "drop")
            stash(name = "glob-alternation", includes = "src/**/*.{txt,md}")
        }
        stage("use") {
            sh("rm -rf src")
            unstash(name = "glob-alternation")
            sh("test -f src/keep.txt")
            sh("test ! -f src/drop.pem")
            sh("ls src")
        }
    }
}
