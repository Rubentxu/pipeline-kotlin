# Automatizaciones locales y autodescubrimiento de PipelineK

PipelineK ejecuta pipelines Kotlin en local y conserva eventos y estado durable. Las siguientes herramientas **son adaptadores de desarrollo** incluidos en `scripts/`, no nuevos verbos del ejecutable `pipelinek`. No utilizan GitHub Actions, servicios remotos ni dependencias Python externas.

## 1. Índice de firmas DSL

El catálogo nativo `pipelinek steps --json` (actualmente esquema `pipelinek.steps/11`) identifica runtime keys, directivas, efectos, capacidades y políticas de replay/recovery. No ofrece por sí solo todas las firmas Kotlin, receivers o sobrecargas. El índice separado se extrae desde los fuentes del módulo de DSL:

```bash
python3 scripts/pipelinek-dsl-index.py --name withCredentials
python3 scripts/pipelinek-dsl-index.py --out /tmp/pipelinek-signatures.json
pipelinek steps --json > /tmp/pipelinek-runtime-catalog.json
python3 scripts/pipelinek-dsl-index.py \
  --runtime-json /tmp/pipelinek-runtime-catalog.json \
  --out /tmp/pipelinek-signatures-with-runtime.json
```

La relación firma ↔ runtime key basada en nombres son candidatos, **no un binding probado por el compilador**. Una firma debe seguir validándose con `pipelinek validate --compile-only` y ejecutar el runtime en entorno confiable para comprobar semántica. El índice es local y derivado del checkout, no necesariamente de la versión instalada. Para plugin, usar el catálogo de composición/trust que se vaya a ejecutar. No generar firmas a mano.

Prueba de regresión local de herramientas Python (sin modificar estado del repo):

```bash
python3 scripts/test_pipelinek_automation_tools.py
```

## 2. Layout opcional del proyecto consumidor

```text
repo/
├── pipeline.kts                # entrada principal, suficiente para proyectos pequeños
├── pipelines/
│   ├── ci/                     # fast/affected/full, si hacen falta
│   ├── gates/                  # contracts, architecture, security
│   ├── certify/                # pruebas del artefacto realmente distribuido
│   └── maintenance/            # operaciones recurrentes idempotentes
└── scripts/                    # wrappers de build y test existentes
```

No imponer plantillas vacías, imports Kotlin inexistentes ni un segundo workflow manager. Los scripts `.pipeline.kts` deben ser entradas ejecutables de forma independiente hasta que PipelineK soporte composición de scripts mediante un contrato real. Usar argumentos, herramientas y políticas comprobadas en el proyecto. Un selector de tests vacío no es PASS.

## 3. Datos locales de tiempo y recursos

PipelineK ya expone `inspect <runId> --db <path> --profile` y señales de telemetría. `scripts/pipelinek-local-report.py` conserva además resúmenes históricos y genera `dashboard.html` **sin JavaScript ni acceso a Internet**, con:

- duración observada de compilación, run desde RunStarted y extremo a extremo; duración de cada stage y de steps emparejados;
- comparación temporal por stage: línea de las últimas 50 muestras, mediana y diferencia entre grupos de 5 runs;
- snapshot de recursos *al iniciar*: número de CPU lógicos, afinidad, cuota cgroup, carga de sistema, memoria host y cgroup, espacio disponible;
- marcadores `PARTIAL` en historiales sin terminal o incompletos; sin duraciones inventadas;
- un fichero JSON por run, evitando escrituras concurrentes sobre un histórico único.

Lugar por defecto: `$XDG_STATE_HOME/pipelinek/projects/<hash-worktree>/reports/` (si falta XDG_STATE_HOME, `~/.local/state`). No versionar por defecto.

```bash
# Registrar entorno justo antes de la ejecución:
python3 scripts/pipelinek-local-report.py snapshot \
  --workspace . --out /tmp/pipelinek-before.json

# Ejecutar el pipeline con la CLI real y conservar su journal:
pipelinek run --db /tmp/pipelinek-run.db --control-root /tmp/pipelinek-control pipeline.kts

# Con el run ID real y terminal confirmado, importar todos los eventos tipados:
pipelinek events "$RUN_ID" --db /tmp/pipelinek-run.db --typed --limit 1000000 \
  | python3 scripts/pipelinek-local-report.py ingest --events - \
      --snapshot /tmp/pipelinek-before.json --workflow ci

# Genera HTML en la ruta XDG del proyecto:
python3 scripts/pipelinek-local-report.py dashboard
```

La CLI `events` tiene límite por defecto de 100 registros. Exigir una ventana suficientemente amplia y comprobar que `RunFinished` está presente y no hubo negativa de lectura; una ejecución sin terminal se informa incompleta.

**Privacidad:** el proyector descarta mensajes, scriptPath, payloads de eventos, variables y stdout/stderr. Los nombres de steps y stages se omiten por defecto y se muestran solo con `--include-names` en ejecuciones confiables. Los snapshots se validan mediante campos permitidos. Ninguna técnica puede asegurar privacidad si un proceso arbitrario recibe secretos y puede emitirlos a archivos o sockets: usar scopes, masking estricto y sandbox real según el riesgo.

**Interpretación:** el snapshot es disponibilidad/carga del entorno antes de la ejecución, **no un contador de CPU/RAM consumidos por la pipeline**. El proyector no mide picos, consumo energético o uso de disco temporal. Ese sampling se implementaría en una fase posterior identificando run, proceso y cgroup. Comparar runs de máquinas distintas requiere normalizar capacidades, toolchain y alcance de pruebas.

## 4. Alcance y verificación

Una ejecución afectada (`SUBSET`) solo mide el subconjunto ejecutado. Un gate FULL lo decide el proyecto consumidor y una certificación de release requiere comprobar el artefacto distribuido. Los adaptadores de reportes no cambian esas políticas, el scheduler ni el resultado del motor; son observadores independientes de hechos existentes.

## 5. Reanudación local tras reinicio, caída del proceso o pérdida de alimentación

**Se reutilizan las capacidades durables del motor; no se reimplementa el replay.** El journal SQLite, el control root, los cursores, el lease/fencing y las políticas de replay siguen siendo autoridad de PipelineK. El helper `pipelinek-run-with-metrics.py` añade sesiones persistidas y una interfaz reproducible a partir de cualquier proyecto que tenga un `.pipeline.kts`.

Herramientas entregadas junto a la distribución instalada (no requieren checkout de PipelineK):

```bash
PIPELINEK_BIN="$(command -v pipelinek)"
PIPELINEK_HOME="$(cd "$(dirname "$(readlink -f "$PIPELINEK_BIN")")/.." && pwd)"
RUNNER="$PIPELINEK_HOME/tools/pipelinek-run-with-metrics.py"

# Tras iniciar el proyecto: si no existe una sesión pendiente crea un run;
# si ya existe una interrumpida y segura, pide a PipelineK que la reanude.
python3 "$RUNNER" --project . --script pipeline.kts --workflow ci --resume-or-new

# Después de reiniciar la máquina, revisar el estado antes de actuar:
python3 "$RUNNER" --project . --script pipeline.kts --workflow ci --status

# Continuar estrictamente la sesión interrumpida, sin abrir una nueva:
python3 "$RUNNER" --project . --script pipeline.kts --workflow ci --resume
```

Las pruebas locales de este adaptador, independientes de un servidor remoto, se ejecutan con:

```bash
python3 scripts/test_pipelinek_resume_state.py
python3 scripts/test_pipelinek_metrics_runner.py
```

Los ejemplos presuponen el layout estándar de distribución (`bin/pipelinek` y `tools/`) y POSIX con `fcntl`. El script del proyecto puede estar bajo `pipelines/` con `--script pipelines/ci/affected.pipeline.kts`; la herramienta no presupone lenguaje ni estructura del código. Cada workflow usa un lock OS local, sesiones separadas, directorios privados y journal/control root persistentes fuera del repositorio bajo XDG State. El helper no necesita GitHub Actions, una base de datos externa ni un proceso residente.

**La selección es conservadora.** La reanudación exige exactamente una sesión elegible, igual identidad de PipelineK instalado, mismos argumentos (comparados por hash), mismo script y los mismos inputs visibles para Git. Un proyecto sin Git se admite para ejecutar, pero solo puede reanudarse con la aceptación expresa `--accept-unverified-inputs`: no podemos certificar sus inputs por defecto. Los ficheros ignorados, secretos, caches, herramientas externas y servicios no se capturan íntegramente; sus cambios deben controlarse mediante la política del proyecto. Para varios runs pendientes, usar `--session <uuid>` después de revisar `--status`, nunca seleccionar arbitrariamente.

**Un cierre observado es definitivo.** Si el run tiene un terminal, `--resume` se niega: no sería recuperación tras caída. Si los resultados externos son inciertos, no se vuelve a ejecutar un publish/deploy/saga como si fuera un test idempotente. Una operación cuyo terminal ya está en el journal puede reconciliarse mediante:

```bash
pipelinek runs recover <runId> --db <journal.db> --control-root <control-dir> --dry-run
# Si el resultado es admisible, el operador puede autorizar la recuperación real.
```

`runs recover` **no relanza efectos**; reconcilia exclusivamente hechos ya persistidos. `pipelinek run --resume --db ... --control-root ... <script>` es la vía nativa que reusa resultados y aplica la política de recuperación de operaciones. Un lease OS de otro proceso o una operación externa con estado ambiguo debe provocar rechazo y diagnóstico, no doble ejecución.

El helper guarda `session.json` de forma atómica con sincronización a disco, más un snapshot de recursos y una muestra de uso de proceso por intento. Recupera la identidad de lanzamiento desde el comprobante `pipelinek.launch/1` o, tras una caída temprana, desde el registro durable `last-run`, únicamente si este es inequívoco. No guarda comandos, variables ni credenciales; persiste digests de la identidad. La pérdida/corrupción del propio journal o de los datos fuera de sus garantías de persistencia no se arregla inventando un checkpoint: se informa como `BLOCKED`.

**Métricas tras interrupción:** el tiempo de calendario entre eventos puede incluir un periodo con la máquina apagada. Las duraciones deben etiquetarse como observadas con huecos; no presentarlas como CPU activa ni sumar stages paralelos. Un dashboard no decide si una recuperación es segura.

Un timer o servicio `systemd --user` local puede invocar `--resume-or-new` tras iniciar sesión, solo con autorización del responsable del proyecto y secretos disponibles. El runner no instala servicios ni intenta automatizar permisos o autorizaciones humanas.
