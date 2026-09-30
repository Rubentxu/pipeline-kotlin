# Guía para la skill de PipelineK en uso agéntico

## 1. Regla principal

Cuando una herramienta necesite identidad/credenciales, la skill no debe guiar al agente a obtener el secreto.

### Evitar

```bash
TOKEN=$(some-secret-command)
export GITHUB_TOKEN="$TOKEN"
curl -H "Authorization: Bearer $TOKEN" ...
git clone https://TOKEN@host/repo
```

### Preferir

```bash
pipelinek command --profile github-release -- gh release create ...
pipelinek step scm-git.checkout ... --profile github-read
```

Si todavía no existe profile/adapter, la skill debe pedir/usar una modalidad explícita y mostrar la posture degradada; nunca fingir secretless.

## 2. Árbol de decisión

```text
¿La operación necesita credenciales?
 ├─ no  → command/step normal
 └─ sí
    ├─ existe profile compatible
    │    └─ usar profile
    └─ no
         ├─ provider+adapter tipado disponible
         │    └─ construir/use spec sin leer bytes
         └─ sólo compatibilidad env/file
              └─ exigir posture degradada explícita o fallar
```

## 3. Elegir `command`, `sh`, `step`

```text
command → programa + argv, sin sintaxis shell necesaria
sh      → pipes, redirects, loops, expansión shell deliberada
step    → operación PipelineK registrada y tipada
flow    → varias operaciones efímeras cuando esté disponible
run     → pipeline.kts durable
```

La skill no debe presentar `command` V1 como shell-free: internamente puede bajar a `core.sh`.

## 4. Descubrimiento

Antes de inventar shell complejo, preferir introspección:

```bash
pipelinek steps list --json
pipelinek steps describe <id> --json
pipelinek profiles list --json
pipelinek profiles describe <name> --json
```

Si existe un Step específico, suele ser preferible a reconstruirlo con shell porque ofrece input/output tipado y capabilities declaradas.

## 5. Filtros y salida

Para PipelineK actual con NDJSON:

```bash
pipelinek run pipeline.kts |
  jq -c 'select(.kind == "RunFinished") | {runId, outcome}'

pipelinek run pipeline.kts |
  jq -c 'select(.kind == "StepFailed") | {failureKind, message}'

pipelinek run pipeline.kts |
  jq -c 'select(.kind == "StepFailed" or .kind == "RunFinished")'
```

Cuando exista OutputPolicy nativa:

```bash
pipelinek ... --output agent
pipelinek ... --output summary
pipelinek ... --events failed,finished
```

Los filtros son de presentación. Nunca se usan para “ocultar” un secreto antes de que llegue al redactor; el runtime debe sanitizar primero.

## 6. Logging de comandos

La skill puede registrar intención:

```text
profile=github-release
tool=gh
operation=release.create
```

pero no debe reconstruir ni imprimir config temporal, session handles o valores de environment sensibles.

## 7. Fallback seguro

Si el adapter no puede satisfacer `minimumPosture`, la skill debe detener esa vía y reportar la incompatibilidad. No debe auto-degradar de `STRONG_SECRETLESS` a env/raw-file.

