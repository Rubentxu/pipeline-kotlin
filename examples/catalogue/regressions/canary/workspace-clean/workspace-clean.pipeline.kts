// The subject is an ABSENCE: `cleanWs` removes the paths it matches, and
// what it does NOT match has to survive.
//
// The effect of a cleanup is invisible by nature: a scenario that ran the
// step and checked the exit code passed just as happily when the step
// deleted nothing, and leaving another run's files behind is exactly the
// failure the step exists to prevent. So the assertion is about what is
// NOT there.
//
// `test ! -e` and not `test ! -f`: cleanWs is documented over patterns and
// not over regular files, and a pattern match that removed the directory
// itself would satisfy `-f` on a leftover path differently from `-e`.
// `-e` asks the only question that matters: did anything survive.
pipeline {
    stages {
        stage("clean") {
            sh("mkdir -p scratch && printf 'gone\\n' > scratch/gone.txt && printf 'kept\\n' > scratch/kept.txt")
            cleanWs(patterns = listOf("scratch/gone.txt"))
            sh("test ! -e scratch/gone.txt && test -e scratch/kept.txt")
        }
    }
}
