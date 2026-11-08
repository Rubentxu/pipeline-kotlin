// Canary Rust con `cargo build --release` + `cargo test` contra un crate
// vendoreado en /opt/rust-canary dentro de la imagen de certificacion.
//
// El container se ejecuta con --read-only, asi que cargo
// no puede escribir Cargo.lock ni target/ dentro de /opt/rust-canary. Solucion:
// copiar el crate a /workspace/rust-canary (que se monta rw) y compilar alli.
//
//   1) stage build:    `cargo build --release` -> exit 0; side effect: binario en /workspace/rust-canary/target/release/
//   2) stage test:     `cargo test`            -> exit 0; side effect: "test result: ok. 2 passed"
//   3) stage verify:   sha256 del binario + assert del output `rust-canary 3`
pipeline {
    stages {
        stage("rust-prepare") {
            sh("set -e; rm -rf /workspace/rust-canary && cp -r /opt/rust-canary /workspace/rust-canary")
        }
        stage("rust-build") {
            sh("set -e; cd /workspace/rust-canary && cargo build --release")
        }
        stage("rust-test") {
            sh("set -e; cd /workspace/rust-canary && cargo test")
        }
        stage("rust-verify") {
            sh("set -e; mkdir -p /workspace/out && sha256sum /workspace/rust-canary/target/release/rust-canary | tee /workspace/out/har010-binary.sha256 && /workspace/rust-canary/target/release/rust-canary | grep -q 'rust-canary 3' && cargo --version | tee /workspace/out/har010-cargo-version.txt")
        }
    }
}
