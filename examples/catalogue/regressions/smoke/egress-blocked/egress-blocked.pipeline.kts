// Caso negativo (seguridad): el aislamiento de red del container bloquea
// cualquier intento de egress. Un escenario que intente curl a Internet debe
// fallar rapido por timeout/refused, NO por timeout largo.
//
// Oraculo externo:
//   1) exit != 0 (curl no puede conectar)
//   2) elapsed < 30s (timeout rapido, no llega al timeout de 600s del ejecutor)
//   3) stderr contiene "Network is unreachable" / "Connection refused" / "Could not resolve"
//   4) NO hay side effect fuera del workspace (sin write a /state, sin daemon)
//
// Si el escenario PASARA con exit=0, eso seria un DEFECTO DE SEGURIDAD: el
// container tiene red cuando no deberia. El certifier lo marcaria FAIL.
//
// Tambien valida el negativo de socket: el harness monta solo el engine del
// usuario; un intento de hablar al socket del engine desde el container debe
// fallar. Aqui solo validamos el lado de red.
pipeline {
    stages {
        stage("no-egress") {
            sh("mkdir -p /workspace/out && (timeout 10 curl -sS -o /dev/null -w '%{http_code}\\n' https://example.com 2> /workspace/out/har022-egress.stderr; echo exit=$? > /workspace/out/har022-egress.exit) || true; test -s /workspace/out/har022-egress.stderr; grep -qE 'Network is unreachable|Connection refused|Could not resolve|Connection timed out|Operation not permitted' /workspace/out/har022-egress.stderr")
        }
        stage("no-dns") {
            sh("mkdir -p /workspace/out && (timeout 5 getent hosts example.com 2> /workspace/out/har022-dns.stderr; echo exit=$? > /workspace/out/har022-dns.exit) || true; test -s /workspace/out/har022-dns.stderr || test \"$(cat /workspace/out/har022-dns.exit | grep -oE '[0-9]+')\" != \"0\"")
        }
    }
}
