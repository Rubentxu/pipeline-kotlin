// The effect of `cleanWs` is an ABSENCE: the matched paths must be gone. A
// scenario that only ran the step and checked the exit code passed just as
// happily when the step deleted nothing, and leaving another run's files on
// disk is exactly the failure this step exists to prevent. So the assertion
// has to be about what is NOT there.
//
// `test ! -e` rather than `test ! -f`: cleanWs is documented over patterns,
// not over regular files, and a pattern match that removed the directory
// itself would satisfy `-f` on a leftover path differently from `-e`. `-e`
// asks the only question that matters here -- did anything at all survive.
pipeline {
    stages {
        stage("cleanws-step") {
            sh("mkdir -p test && touch test/a.txt test/b.txt")
            cleanWs(patterns = listOf("test/**"))
            sh("test ! -e test/a.txt && test ! -e test/b.txt")
        }
    }
}