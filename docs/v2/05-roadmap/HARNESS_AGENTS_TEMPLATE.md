# AGENTS.md — Plantilla para `Rubentxu/pipelinek-release-harness`

> **Estado:** PLANTILLA entregada por pipeline-kotlin el 2026-09-24T10:39Z.
> **Uso:** pegar este contenido como `AGENTS.md` raíz del repositorio
> `Rubentxu/pipelinek-release-harness` cuando se inicialice (o como PR inicial).
> **Source of authority:** directiva del operador 2026-09-24T10:09Z + 10:25Z +
> 10:39Z + reglas en `pipeline-kotlin/docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`
> + handover material en `pipeline-kotlin/docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md`.

## Release candidates — certificación y promoción

1. **Responsabilidad:** este repositorio recibe candidatas inmutables de `Rubentxu/pipeline-kotlin` (ZIP reproducible con SHA-256 + manifiesto) y emite un veredicto estructurado por candidata. Si la candidata supera el gate, este repositorio la **promueve a release estable** sobre `Rubentxu/pipeline-kotlin`. La promoción sube los mismos bytes del ZIP; nunca reconstruye.

2. **Tests externos:** este repositorio ejecuta las pruebas externas contra la candidata instalada: proyectos reales (PetClinic, Hello-World, Spoon-Knife, etc.), sandbox contenedor (Podman/Docker), matrices de toolchains (Gradle/Maven/Node), benchmarks, durabilidad bajo kill/restart. NO ejecuta código fuente de PipelineK.

3. **Defectos:** este repositorio abre **una sola issue por defecto del producto** con huella estable `contrato + escenario + tipo + causa`; **nunca** por SHA de candidata. Cierra la issue cuando la corrección se verifique en distribución instalada, con recibo + digest.

4. **Estados de GitHub:** este repositorio publica check runs y commit statuses para PRs de `pipeline-kotlin`, pero el resultado completo y su evidencia persisten en `evidence/<candidate>/`. **Los comentarios sueltos de PR NO son fuente de verdad.**

5. **Identidad separada:** la identidad que verifica (con `Checks: write`) es distinta de la identidad que publica (con `Contents: write`). Ninguna tiene los dos permisos. La separación del verificador y del publicador es un invariante, no una optimización.

6. **Issue deduplicada:** antes de crear una issue, buscar una abierta con la misma huella. Si existe, actualizarla con nueva evidencia en lugar de abrir otra. Si no, crearla.

7. **Continuidad:** un defecto detectado por este repositorio no bloquea la siguiente WU de `pipeline-kotlin`. La candidata bloqueada queda como evidencia hasta que la corrección se reincorpore como nueva candidata.

## Frontera de responsabilidad — pipelinek-release-harness vs pipeline-kotlin

La frontera entre desarrollo y certificación es una propiedad arquitectónica, no una decisión coyuntural. Estas reglas son vinculantes para cualquier trabajo en este repositorio:

1. **Este repo NO modifica el código fuente de PipelineK**. Las correcciones viven en PRs separados contra `Rubentxu/pipeline-kotlin`. El trabajo en este repo es exclusivamente tooling de certificación: scripts Python/Go, manifests, NDJSON schemas, scripts de instalación, evidencia.

2. **Este repo NO emite candidatas.** Las candidatas las emite `pipeline-kotlin`. El trabajo de este repositorio empieza cuando una candidata llega con su manifiesto + ZIP + SHA-256.

3. **Este repo SÍ abre issues de defectos del producto.** Cada defecto reproducible detectado durante la certificación se abre como issue en `Rubentxu/pipelinek-release-harness` (no en `pipeline-kotlin`) con la huella `contrato + escenario + tipo + causa`. La coordenada del SHA de candidata es variable, **no** parte de la huella.

4. **Tests y fixtures que migran aquí**: ver `pipeline-kotlin/docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md` §2 y §5. Calendario por fases: M0 bootstrap, M1 sin sandbox (~14 tests), M2 sandbox (~17 tests), M3 cierre.

5. **Comandos que consume este repo**: el binario de PipelineK (instalable) + los scripts de `scripts/run-pipelinek`, `scripts/verify-rp-043.py`, `scripts/gen-certification-ledger.py` que se copian verbatim desde `pipeline-kotlin` (con sus SHA-256 documentados en el handover).

6. **Estados y evidencias que produce este repo**: `evidence/<candidate>/manifest.json`, `evidence/<candidate>/ndjson/*.ndjson`, `evidence/<candidate>/artifacts/*`, `evidence/<candidate>/verdict.json`, `evidence/ledger.jsonl` (append-only).

7. **No ejecutar la matriz externa desde código fuente**. Todo lo que se ejecuta aquí corre sobre el binario instalado de la candidata (`<installDir>/bin/pipelinek`), con `--db` y `--control-root` aislados por ejecución. El verificador N3A del handover es la metodología base.

8. **El roadmap propio de este repositorio lo escribe su propio agente**. NO importar `RESPONSIBILITY_MIGRATION_ROADMAP.md` como `ROADMAP.md` aquí: es tabla de movimientos, no roadmap. Este repositorio puede usar el inventario del handover como input, pero su roadmap es propio.

9. **Coordinación con el operador**: las WUs concretas del harness (WU-HARNESS-NNN) las planifica el agente de este repo. Las WUs de `pipeline-kotlin` siguen el roadmap de `pipeline-kotlin`. Las dependencias entre las dos las resuelve el operador (no las asume nadie implícitamente).

10. **Publicación de release estable**: cuando una candidata pasa el gate, este repositorio etiqueta y crea el release en `Rubentxu/pipeline-kotlin` usando **los mismos bytes del ZIP verificado** (nunca una reconstrucción). El tag queda firmado por la identidad publicadora (no verificadora).

## Apéndice — Atajos de evidencia

- **Manifiesto de candidata publicado**: https://github.com/Rubentxu/pipeline-kotlin/pull/73 (rama `wu/rp-harness-coordination`, archivo `docs/v2/07-uat/HARNESS_INVENTORY_HANDOVER.md`).
- **Issue de coordinación inicial**: https://github.com/Rubentxu/pipelinek-release-harness/issues/2.
- **Tabla de movimientos desde pipeline-kotlin**: `pipeline-kotlin/docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md` (cambia con el tiempo; consultar siempre la versión más reciente).
- **Reglas espejo en pipeline-kotlin**: bloque `## Frontera de responsabilidad — pipeline-kotlin vs pipelinek-release-harness` en `pipeline-kotlin/AGENTS.md` (deben coincidir en espíritu con este documento).
