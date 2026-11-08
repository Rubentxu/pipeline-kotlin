# Rust Build Neg

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `process`, `shell`, `plugin`

**Resultado esperado:** `failure`

## Que ejercita

La vida de una corrida: como arranca, como acaba y que concluye

*El ejemplo dice de si mismo:*

> Caso negativo: build de Rust que falla por E0308 (tipo incompatible).
> El crate vive en /opt/rust-canary-neg dentro de la imagen de certificacion.
> Esperado: la etapa falla con cargo exit != 0; side effect: archivo .stderr
> en /workspace/out/ contiene un diagnostic de tipo error[E0308].

## Las etapas y los pasos

### 1. `rust-build-neg`

- `sh` — `"set -e; cd /opt/rust-canary-neg && (cargo build 2> /workspace/out/har010-neg.stderr; e...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- toolchain rustup minimal y gcc/libc6-dev presentes en la imagen

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/rust-build-neg/rust-build-neg.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/rust-build-neg/rust-build-neg.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

**Cubre:** `HAR-010`

## Su linaje

- **Nombre historico:** `har010-rust-build-neg.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har010-rust-build-neg.pipeline.kts`
- **Huella del cuerpo:** `sha256:35a2791eb96d856f34cada5ef827be04b5dafe6dca7ee52df392cce5b7db4bb5`
