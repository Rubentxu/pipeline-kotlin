// The subject is the TOOLCHAIN the image provides: it has to be there,
// and the version it reports has to be recorded.
//
// A canary that passes because it found `python3` is not the same claim
// as one that passes because the image SHIPS `python3`, and only the
// second can be repeated on another machine. The receipt carries the
// inventory the harness measured in the image; this canary carries the
// version the scenario itself observed, so the two can be compared
// instead of trusted.
//
// The version is a FILE and not a fixed string: a canary that asserted
// "3.10.12" would break with every image rebuild and would say nothing
// about the image it is certifying.
pipeline {
    stages {
        stage("tools") {
            sh("mkdir -p /workspace/out && python3 --version > /workspace/out/canary-python3.txt 2>&1 && cargo --version > /workspace/out/canary-cargo.txt 2>&1 && test -s /workspace/out/canary-python3.txt && test -s /workspace/out/canary-cargo.txt")
        }
    }
}
