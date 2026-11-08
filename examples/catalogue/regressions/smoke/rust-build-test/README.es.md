# Rust Build Test

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `process`, `shell`, `plugin`

**Resultado esperado:** `success`

## Que ejercita

La vida de una corrida: como arranca, como acaba y que concluye

*El ejemplo dice de si mismo:*

> Canary Rust con 'cargo build --release' + 'cargo test' contra un crate
> vendoreado en /opt/rust-canary dentro de la imagen de certificacion.

## Las etapas y los pasos

### 1. `rust-prepare`

- `sh` — `set -e; rm -rf /workspace/rust-canary && cp -r /opt/rust-canary /workspace/rust-canary`

### 2. `rust-build`

- `sh` — `set -e; cd /workspace/rust-canary && cargo build --release`

### 3. `rust-test`

- `sh` — `set -e; cd /workspace/rust-canary && cargo test`

### 4. `rust-verify`

- `sh` — `"set -e; mkdir -p /workspace/out && sha256sum /workspace/rust-canary/target/release/rus...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/har010-binary.sha256`, `out/har010-cargo-version.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- toolchain rustup minimal y gcc/libc6-dev presentes en la imagen

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/rust-build-test/rust-build-test.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/rust-build-test/rust-build-test.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `har010-rust-build-test.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har010-rust-build-test.pipeline.kts`
- **Huella del cuerpo:** `sha256:99dca1bb35489db92f38679c39b0a8f20075abe5c477d23cf5f0671712edaed5`
