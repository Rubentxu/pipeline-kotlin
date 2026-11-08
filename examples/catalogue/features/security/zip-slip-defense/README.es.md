# Zip Slip Defense

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `security`

**Resultado esperado:** `failure`

## Que ejercita

La frontera de confianza: que bytes de un plugin se pueden ejecutar

*El ejemplo dice de si mismo:*

> Utilities OFFICIAL_PLUGIN live fixture.
> End-to-end defence-in-depth proof of containment on
> BOTH sides of the zip chain. The fixture:
> 1) Creates a benign zip archive (via the typed zipFiles Step).
> 2) Replaces the local-file-header entry-name bytes with a
> parent-segment name "../escaped.txt" using a 'core.sh' one-liner.
> The body / CRC remain valid (we just renamed the entry), so the
> resulting file is parseable as a real zip; only the entry name
> has been smuggled.
> 3) Runs 'core-utils.unzip' against the malicious archive.
> 4) The handler MUST reject the archive with a typed USER failure
> containing "Zip Slip". The pipeline as a whole fails with a
> typed failure.
> Note: this fixture intentionally triggers a typed failure. The launcher
> expects it: the run must fail, and it must fail with that specific
> refusal.

## Las etapas y los pasos

### 1. `zip-slip-defense`

- `sh` — `echo 'benign' > abcdefghijklmn`
- `zipFiles` — `path = "build/utils/malicious.zip", paths = listOf("abcdefghijklmn"), overwrite = true,` — un paso que aporta este plugin (`core-utils`)
- `sh` — `""" python3 - <<'PY' import struct p = 'build/utils/malicious.zip' data = bytearray(ope...`
- `unzip` — `path = "build/utils/malicious.zip"` — un paso que aporta este plugin (`core-utils`)

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `1` |
| conclusion | `failure` |
| stderr contiene | `Zip Slip` |

## Que necesita

- storage-agnostic

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/security/zip-slip-defense/zip-slip-defense.pipeline.kts
pipelinek run      examples/catalogue/features/security/zip-slip-defense/zip-slip-defense.pipeline.kts
```

## Su linaje

- **Nombre historico:** `28-zip-slip-defense.pipeline.kts`
- **Ruta de origen:** `examples/security/28-zip-slip-defense.pipeline.kts`
- **Huella del cuerpo:** `sha256:8f3dff8f373dfee29c87c04a04b620e73ad5eb970a49d84d47b62acd5d3ca089`
- **Huella del origen:** `sha256:f5cf61daa03a5904da178512c0a4620c9064446697abd394239f44343b912271`
