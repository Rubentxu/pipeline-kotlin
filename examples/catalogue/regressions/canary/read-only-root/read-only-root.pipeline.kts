// The subject is the container's ROOT filesystem: it has to refuse a
// write.
//
// The isolation norm says the scenario runs with a read-only root, and a
// scenario that never tries to write outside the workspace cannot tell
// whether the guarantee holds. A root that accepted the write would let
// a scenario tamper with the image it is being certified against, and
// every other guarantee would become meaningless.
//
// The check is the opposite of the usual one: the run PASSES when the
// write is REFUSED. `test -s` on the captured error would be checking the
// wrong thing -- the refusal message comes from the shell itself, not
// from the redirected command, so it is the exit status that is read.
pipeline {
    stages {
        stage("root") {
            sh("mkdir -p /workspace/out; if printf 'x' > /etc/canary-readonly-probe 2>/dev/null; then echo 'ROOT-WAS-WRITABLE'; exit 1; else printf 'root refused the write\\n' > /workspace/out/canary-readonly.txt; fi")
        }
    }
}
