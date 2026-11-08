// Caso negativo: build de Rust que falla por E0308 (tipo incompatible).
// El crate vive en /opt/rust-canary-neg dentro de la imagen de certificacion.
// Esperado: la etapa falla con cargo exit != 0; side effect: archivo .stderr
// en /workspace/out/ contiene un diagnostic de tipo error[E0308].
pipeline {
    stages {
        stage("rust-build-neg") {
            sh("set -e; cd /opt/rust-canary-neg && (cargo build 2> /workspace/out/har010-neg.stderr; echo exit=$? > /workspace/out/har010-neg.exit) || true; test -s /workspace/out/har010-neg.stderr; grep -qE 'error\\[(E[0-9]+)\\]' /workspace/out/har010-neg.stderr || (echo 'expected E-level diagnostic not found'; cat /workspace/out/har010-neg.stderr; exit 1)")
        }
    }
}
