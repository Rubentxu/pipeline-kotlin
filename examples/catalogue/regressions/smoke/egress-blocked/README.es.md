# Egress Blocked

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `egress`, `security`, `release`

**Resultado esperado:** `success`

## Que ejercita

Que pasa cuando un paso intenta llegar a la red

*El ejemplo dice de si mismo:*

> Caso negativo (seguridad): el aislamiento de red del container bloquea
> cualquier intento de egress. Un escenario que intente curl a Internet debe
> fallar rapido por timeout/refused, NO por timeout largo.

## Las etapas y los pasos

### 1. `no-egress`

- `sh` — `"mkdir -p /workspace/out && (timeout 10 curl -sS -o /dev/null -w '%{http_code}\\n' http...`

### 2. `no-dns`

- `sh` — `"mkdir -p /workspace/out && (timeout 5 getent hosts example.com 2> /workspace/out/har02...`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |
| ficheros | `out/har022-egress.stderr`, `out/har022-egress.exit`, `out/har022-dns.stderr`, `out/har022-dns.exit` |

## Que necesita

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- la imagen se ejecuta con --cpus 2 --memory 2g --pids-limit 256 --cap-drop ALL --network none --read-only (aislamiento de H0.3)

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/regressions/smoke/egress-blocked/egress-blocked.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/egress-blocked/egress-blocked.pipeline.kts
```

**Estado del instrumento:** `PASS` — la cobertura del instrumento que lo juzga, no el veredicto de su ultima ejecucion.

## Su linaje

- **Nombre historico:** `har022-egress-blocked.pipeline.kts`
- **Ruta de origen:** `scenarios/smoke/har022-egress-blocked.pipeline.kts`
- **Huella del cuerpo:** `sha256:45d18b44efca4fef73d6feb14ddc7726afe7121b477334cbbfd3d44a2da9a751`
