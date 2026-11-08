# No Secret Leak

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `security`, `secret`

**Resultado esperado:** `success`

## Que ejercita

Las fronteras que una corrida no puede cruzar

*El ejemplo dice de si mismo:*

> Regresion de no-filtracion de secretos en logs de pipeline-kotlin.
> El canario emite contenido "normal" (mensajes de compilacion, paths,
> resultados de tests) y exige que el detector de secretos (harness/secrets.py)
> NO encuentre ningun patron reconocible. Si PASARA con hallazgos, seria un
> DEFECTO DE SEGURIDAD: el log esta filtrando un secreto.

## Las etapas y los pasos

### 1. `clean-build`

- `sh` — `echo 'compiling rust-canary v0.1.0'`
- `sh` — `echo 'all 2 tests passed'`
- `sh` — `ls -la /workspace | head -3`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| stdout contiene | `compiling rust-canary` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- H0.* NO monta SecretProvider: el canario solo comprueba que el shell no filtra patrones (un vault real es H4)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/no-secret-leak/no-secret-leak.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/no-secret-leak/no-secret-leak.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

**Cubre:** `HAR-023`

## Su linaje

- **Nombre historico:** `har023-no-secret-leak.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har023-no-secret-leak.pipeline.kts`
- **Huella del cuerpo:** `sha256:28b0d1f46a78383b6194fc4083d5c02bbc0e88618762b6ba66b71ffddce8633a`
