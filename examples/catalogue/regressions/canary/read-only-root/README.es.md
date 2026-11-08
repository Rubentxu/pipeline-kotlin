# Read Only Root

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `security`, `workspace`

**Resultado esperado:** `success`

## Que ejercita

Las fronteras que una corrida no puede cruzar

*El ejemplo dice de si mismo:*

> The subject is the container's ROOT filesystem: it has to refuse a
> write.

## Las etapas y los pasos

### 1. `root`

- `sh` — `"mkdir -p /workspace/out; if printf 'x' > /etc/canary-readonly-probe 2>/dev/null; then...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/canary-readonly.txt` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/canary/read-only-root/read-only-root.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/read-only-root/read-only-root.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `read-only-root.pipeline.kts`
- **Ruta de origen:** `scenarios/canary/read-only-root.pipeline.kts`
- **Huella del cuerpo:** `sha256:0a9d3df3b6cdfd6185c03635162555159f12d564033cbc1e4360e5a6e2deee02`
