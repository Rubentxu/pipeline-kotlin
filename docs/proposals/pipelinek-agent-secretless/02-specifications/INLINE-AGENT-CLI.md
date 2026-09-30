# CLI inline para agentes

## 1. Propósito

Dar al agente una interfaz estructurada que evite escribir pipelines temporales en el repo y reduzca la necesidad de shell ad-hoc.

## 2. Niveles

```text
L0  command/shell       → una operación de proceso
L1  step                → una operación tipada del registry
L2  flow                → workflow efímero multi-Step
L3  run pipeline.kts    → workflow durable de proyecto
```

Todos comparten coordinator, registry, capabilities y eventos.

## 3. `pipelinek command`

Propuesta inicial:

```bash
pipelinek command --profile github-release -- \
  gh release create v1.2.0 dist/app.zip
```

Semántica V1:

```text
argv → canonical shell encoder → core.sh → existing durable shell spine
```

No se afirma que sea shell-free.

Puede conservarse `pipelinek exec` como nombre candidato futuro, pero no debe introducir una segunda implementación.

## 4. `pipelinek sh`

Para shell real:

```bash
pipelinek sh --profile nexus-release -- './gradlew publish | tee publish.log'
```

El agente debe preferir `command` cuando no necesita sintaxis shell y `sh` cuando sí la necesita.

## 5. `pipelinek step`

```bash
pipelinek step scm-git.checkout \
  --set url=https://github.com/Rubentxu/pipeline-kotlin \
  --set branch=main \
  --profile github-read
```

El parser CLI debe usar el input schema/codec del `StepDefinition`. Para inputs complejos:

```bash
pipelinek step <id> --input-json @request.json
```

## 6. Introspección

Necesaria para agentes:

```bash
pipelinek steps list --json
pipelinek steps describe scm-git.checkout --json
pipelinek profiles list --json
pipelinek profiles describe github-release --json
```

`steps describe` debe devolver al menos:

- id/version;
- input/output schema;
- required capabilities;
- replay policy;
- credential requirements si son estáticos o su categoría si son input-dependent;
- side-effect class;
- installed plugin provenance.

## 7. `pipelinek flow`

Fase posterior:

```bash
pipelinek flow --stdin <<'JSON'
{
  "profile": "release",
  "steps": [
    {"step":"core.sha256","input":{"file":"dist/app.zip"}},
    {"step":"github.release.create","input":{"tag":"v1.2.0"}}
  ]
}
JSON
```

No crea `.pipeline.kts`; construye un transient PipelineSpec y usa el coordinator existente.

## 8. Estado y no-morralla

Default:

```text
XDG_STATE_HOME/pipelinek/agent-runs/<run-id>
XDG_CACHE_HOME/pipelinek/...
XDG_CONFIG_HOME/pipelinek/...
```

Nunca se escribe configuración persistente en el repo salvo petición explícita.

## 9. Exit contract

Conservar la convención actual siempre que sea compatible:

```text
0 = operación/pipeline success
1 = ejecución válida que terminó en failure
2 = invocación/admisión/configuración inválida
```

Errores de posture/policy/capability son admission failures y deben distinguirse de un Step que ejecutó y falló.

## 10. Agent mode

Atajo:

```bash
pipelinek ... --output agent
```

Debe producir información compacta, estructurada y ya redacted:

- run id;
- step id;
- outcome;
- typed result seleccionado;
- failure kind/message sanitizado;
- artefactos/referencias relevantes;
- postura de credenciales usada, sin material secreto.

