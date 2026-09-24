# HARNESS INVENTORY HANDOVER — Material de entrada para `Rubentxu/pipelinek-release-harness`

> **Estado:** HANDOVER (entrega acordada por el operador el 2026-09-24T10:25Z).
> **Ámbito:** entrega unilateral desde `Rubentxu/pipeline-kotlin` al repositorio independiente `Rubentxu/pipelinek-release-harness` (aún no creado).
> **Source of authority:** directiva 2026-09-24T10:09Z (separación desarrollo ↔ certificación ↔ publicación) + directiva 2026-09-24T10:25Z ("inventario aprobado como material de entrada para el harness, no como un nuevo roadmap de PipelineK").
> **Modelo de transporte:** en ausencia del repo del harness, este archivo se preserva en `pipeline-kotlin/docs/v2/07-uat/` y viajará al repo del harness cuando éste exista. La cabecera "HANDOVER" y el §7 procedimiento de traslado son el contrato de entrega.

## 1. Resumen ejecutivo

Este archivo contiene el **inventario exhaustivo** de activos del repositorio `Rubentxu/pipeline-kotlin` que son **reutilizables**, **adaptables** o **inviolables** desde la perspectiva del nuevo repositorio `Rubentxu/pipelinek-release-harness`.

NO contiene un roadmap del harness. El roadmap del harness es responsabilidad de su propio agente y debe vivir en su propio repositorio. Aquí sólo se entrega la materia prima para que ese roadmap se construya.

## 2. Activos directamente movibles (sin acoplamiento interno)

Estos activos son ficheros independientes y pueden copiarse verbatim al harness sin tocar el código de PipelineK.

| Activo | Path en pipeline-kotlin | SHA-256 | Notas |
|---|---|---|---|
| Lanzador bash binario | `scripts/run-pipelinek.sh` | `e38486136894348c64d78491789546227d75838d4ff6a543dcfd61ba7dd8e719` | Portátil; instalar ZIP y ejecutar binario. |
| Lanzador bash con estado externo | `scripts/run-pipelinek` | `13c821d28f1fc4b91f23eb316b7620836eff8864c3b3deadc0cb16eac4c1918f` | Mantiene `--db` y `--control-root` separados. |
| Verificador externo N3A | `scripts/verify-rp-043.py` | `e1f295ae46720eda57d14cab894730342e8939c00949c592f76cb9d3ae5dd360` | 503 líneas. Metodología de 4 escenarios sobre `--db` y `--control-root`. |
| Generador ledger de certificación | `scripts/gen-certification-ledger.py` | `be6dc0014db42ee2a9e5364ab5d74423b7540390c5b8f0bd1bf6cd45a489e07b` | Generador determinístico; en el harness debe adaptarse a la realidad de certificación (no a Steps). |
| Manifiesto de candidata (formato) | `/tmp/candidate-262cc11e.MANIFEST.md` (formato, no su contenido) | n/a (formato) | Trazabilidad operativa: identificadores inmutables + artefactos con SHA-256 + verificadores ejecutados + troubleshooting + identidad material. |

## 3. Activos adaptables (requieren desacoplamiento)

Estos activos viven acoplados al classpath/test runtime de PipelineK. Para ejecutarlos desde el harness hay que refactorizarlos para que el binario y la candidata sean parámetros externos.

| Activo | Path en pipeline-kotlin | Adaptación necesaria |
|---|---|---|
| `UatCompat001CorpusSmokeRunTest` | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatCompat001CorpusSmokeRunTest.kt` | Refactorizar: el binario y el `control-root` son argumentos; el corpus se monta desde `v2/compatibility/*.pipeline.kts`. |
| `UatDsl001JenkinsFamiliarityTest` y familia `UatDsl*` | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatDsl001..008*.kt` | Idem. Algunas UATs necesitan el `installDir` parametrizado. |
| `UatLocal007SandboxProfileTest` | `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal007SandboxProfileTest.kt` | Migrar cuando el harness introduzca sandbox de tipo container. La lógica del sandbox es agnóstica al motor. |
| 33 fixtures `*.pipeline.kts` | `v2/compatibility/01-basic.pipeline.kts` … `v2/compatibility/33-...pipeline.kts` | Catálogo de escenarios. Trivialmente parametrizables: pasan a ser inputs del harness. |
| `.pipeline.kts` canario (N2 dogfooding) | `v2/.pipeline.kts` (72 líneas; sha256 `262cc11e` run) | Adaptar como plantilla del escenario end-to-end del harness. |

## 4. Activos inviolables (no se mueven — viven con el producto)

| Concepto | Path / por qué |
|---|---|
| Suite de contratos internos | Tests de `pipeline-domain`, `pipeline-events`, `pipeline-protocol`, etc. Son la red de seguridad del producto, no del proceso de certificación. |
| Tests de Step (`StepContractSuite`) | Acoplados a Step IR/codec/registro; viven con el código del Step. |
| ADRs vigentes (`docs/v2/03-architecture-decisions/`) | Autoridades normativas del producto, no del proceso de certificación. |
| Recibos UAT (WIP del operador) | `docs/v2/07-uat/WU_RP_053_*`, `WU_RP_043_*`, etc. Históricos; sólo el harness necesita su **propia** colección de recibos de certificación. |

## 5. Dependencias técnicas que el harness necesitará

Esto NO es un inventario de "cosas a mover", sino el conjunto de capacidades que el harness debe poder invocar para certificar una candidata.

### 5.1 Para instalar una candidata
- ZIP reproducible con SHA-256 verificable.
- Path absoluto del binario launcher (`<installDir>/bin/pipelinek`).
- Variable `JAVA_HOME` (JDK 21+ o el que use la candidata).
- Variable `PATH` con `bin/` antepuesto.

### 5.2 Para ejecutar pruebas externas
- Directorio de estado **limpio y dedicado** (sin workaround de `purge_state_dir`). El verificador N3A vive y muere sobre esto.
- Acceso de red saliente para `git clone` de los proyectos externos canónicos (PetClinic, Hello-World, etc.).
- `bash`, `git`, `unzip` disponibles.
- En sistemas donde la ejecución va en contenedor: `podman` o `docker` + `--network=host` (o equivalente) para la instalación del binario.

### 5.3 Para producir evidencia verificable
- Capacidad de escribir NDJSON línea por escenario.
- Capacidad de retener junit-xml + logs + sidecars como artefactos firmados.
- Capacidad de generar un `verdict.json` con la decisión de promoción a partir del resultado agregado.

### 5.4 Para publicar el estado en GitHub (verificador/publicador)
- Una identidad con `Checks: write` para crear check runs / commit statuses sobre la SHA candidata. Inicialmente se usa **commit statuses** (sin necesidad de GitHub App).
- Una identidad separada con `Contents: write` para etiquetar y crear la release sobre el repo de `pipeline-kotlin`.
- Ninguna identidad debe tener los dos permisos. **Separación del verificador y del publicador** es un invariante, no una optimización.

### 5.5 Para promover una release
- Permiso `Contents: write` sobre `Rubentxu/pipeline-kotlin` (del lado del publicador).
- Tag firmado o hash reproducible de ZIP publicación.
- **Nunca reconstruir** el ZIP; promover los mismos bytes verificados.

## 6. Criterios de equivalencia (qué necesita el harness antes de retirar una UAT del origen)

Estos criterios son el contrato que separa "el harness ejecuta el mismo test" de "el harness produce un sustituto verificable".

1. La UAT original en pipeline-kotlin debe ejecutarse en verde sobre la candidata `wu/rp-043-integration-clean` (HEAD `262cc11e`) y dejar un junit-xml con su SHA.
2. El harness debe ejecutar la versión migrada sobre **la misma candidata** y producir un NDJSON con el mismo conjunto de escenarios.
3. La huella de defecto (input + síntoma + tipo + causa) debe poder compararse 1-a-1 entre el resultado del harness y el junit-xml del origen.
4. Si la UAT detecta un fallo en el origen, la versión migrada debe detectarlo también. Si la UAT pasa en el origen, debe pasar también en el harness.
5. El tiempo de extremo a extremo **no debe empeorar** sin justificación. Si empeora, la UAT vuelve al origen con un recibo explicando por qué.

## 7. Procedimiento de traslado

Cuando el operador cree el repositorio `Rubentxu/pipelinek-release-harness`:

1. Crear el repo vacío en GitHub (`Rubentxu/pipelinek-release-harness`).
2. Inicializar el `AGENTS.md` con el bloque complementario al de `pipeline-kotlin` (responsabilidades del harness, no del productor de candidatas).
3. Subir este archivo a `docs/handover/2026-09-24-pipeline-kotlin-inventory.md`.
4. Copiar verbatim los scripts del §2 a `harness/bin/` (si se decide) o instalarlos como dependencia.
5. NO incorporar este archivo como `ROADMAP.md` del harness; el roadmap del harness lo escribe su propio agente.
6. NO importar fixtures ni UATs del §3 sin refactor previo (el refactor es del harness, no de pipeline-kotlin).

## 8. Estado de entrega y avisos

- **NO** comitear este archivo todavía en `pipeline-kotlin` si el operador prefiere entregar al harness primero vía otro canal.
- **NO** importar este archivo automáticamente al harness si no existe repo.
- **NO** afirmar una migración completada porque la documentación haya viajado.
- **NO** retirar tests del origen hasta que el sustituto esté verificado contra el mismo defecto (criterios §6).

## 9. Identidad material del envío

| Item | Valor |
|---|---|
| Remitente | `Rubentxu/pipeline-kotlin` HEAD `262cc11e` sobre `origin/main` `74b40a65` |
| Destino | `Rubentxu/pipelinek-release-harness` (aún no creado) |
| Rama candidata WU-RP-043 | `wu/rp-043-integration-clean` |
| ZIP candidata (SHA-256) | `71394ec95532d62b8e1899d2ba85e03bfaf4b24af0ed8af4b16fb6c98b267954` |
| Binario launcher (SHA-256) | `045412d24022aff5090507b4340a2b327041c05ddc1736028e0d28209985acd8` |
| Manifiesto de la candidata | `/tmp/candidate-262cc11e.MANIFEST.md` |
| Recibos de los scripts | `scripts/*_receipt.md`, `docs/v2/07-uat/WU_RP_043_*` |
| Estado de certificación de la candidata | `CERTIFIED_WITH_DISCLOSURE` (round gate integral del repo TIMEOUT, no verde integral) |

## 10. Métricas baseline recogidas en pipeline-kotlin (2026-09-24T10:14Z)

Datos para que el harness no cometa el error de confundir tiempo de caché con tiempo de ejecución.

| Comando | Wall | Notas |
|---|---|---|
| `:pipeline-domain:compileKotlin` (warm) | **2 s** | 1 actionable task: 1 up-to-date. UP-TO-DATE sólo verifica hashes. |
| `:pipeline-events:compileKotlin` (warm) | **2 s** | 5 actionable tasks: 5 up-to-date. |
| `:pipeline-credentials-api:test` (warm) | **2 s** | 1 from cache, 10 up-to-date. Cache caliente sirve la respuesta. |
| `:pipeline-credentials-api:test --rerun-tasks` (forzado) | **47 s** | 11 actionable tasks: 11 executed. |
| `:pipeline-binding-factory:test --rerun-tasks` (forzado) | **17 s** | 13 actionable tasks: 13 executed. |

**Aviso:** un `test warm ~2s` mide el tiempo de reutilizar resultados, no el de ejecutar los tests. Los `17–47s` con `--rerun-tasks` son la referencia para estimar la ejecución real. Ninguna de las dos mediciones justifica por sí sola retirar cobertura; el sustituto debe existir primero.
