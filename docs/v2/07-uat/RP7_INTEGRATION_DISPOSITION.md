# RP7-INTEGRATION — disposición e integración de los paquetes de evolución

**Tipo:** WU de disposición. **No implementa funcionalidad.**
**Precedencia:** RP6-CLOSEOUT (`docs/v2/07-uat/RP6_CLOSEOUT.md`).

**Problema que resuelve.** Hay hoy tres colas que se describen como planes y sólo una
es la secuencia operativa:

```text
docs/v2/05-roadmap/ROADMAP.md            ← ÚNICA secuencia operativa (AGENTS.md)
docs/pipelinek-semantic-evolution/08-roadmap.md    ← S0..S8, muy detallado, NO integrado
docs/proposals/pipelinek-agent-secretless/          ← ASX-0..7, NO integrado
```

`AGENTS.md` nombra el `ROADMAP.md` como secuencia única mientras dos paquetes
enteros esperan dentro de él sin figurar. Un roadmap que no contiene su propio
trabajo deja de ser un roadmap.

---

## 1. Reconciliación de S0–S2 contra el código actual

`docs/pipelinek-semantic-evolution/08-roadmap.md` se escribió contra un árbol de
septiembre. Buena parte de S0–S2 ya está implementada y el paquete no lo sabe. Esta
tabla es el resultado de mirar el código, no el documento.

| Ítem | Intención | Estado real | Evidencia |
|---|---|---|---|
| S0.1 | reconciliar identidad Git/SDDK | **SATISFECHO** | checkpoint de reconciliación en el ledger de `rp6c-http-request`, seq 7, sin eventos retrospectivos fabricados |
| S0.2 | DSL Surface Manifest v1 | **SATISFECHO** | `docs/v2/surface/DSL_SURFACE_MANIFEST.md`; la fila `httpRequest` ya dice OFFICIAL_PLUGIN y enumera las capacidades |
| S0.3 | corregir semantic drops | **PARCIAL** | el KDoc de `HttpMethod` era uno y está corregido; no hay barrido sistemático |
| S0.4 | semantic witness suite | **PARCIAL** | hay witnesses reais (`Lfc2HttpOfficiallyPluginBoundaryFitnessTest`, `CoordinatorGrowthGuardrailTest`), no una suite con ese nombre |
| S0.5 | release gate | **NOT_RUN** | `PRODUCT-GATE` está `BLOCKED_EXTERNAL`: no hay superficie de CI desde `754ddda0` |
| S1.1 | ADTs y registries | **SATISFECHO** | `RegistryExecutionBoundary`, `RegistryExecutionPreparation`, `StepRegistry`; el slice de PR-020 extrajo la estructura del coordinator |
| S1.2 | fases del intérprete de stage | **SATISFECHO** | H2 `BodyExecutionEngine`, PR-019 (recovery como puerto), PR-020 (colapso del coordinator) |
| S1.3 | eventos | **SATISFECHO** | eventos tipados por Step; `RedactingEventSink` en `pipeline-credentials-api` |
| S1.4 | fixture de directiva externa | **SATISFECHO** | `Discovered external directive plugins: example.lock.LockContributor` aparece 5 veces en el log del gate de `530ffa98` |
| S2.1 | `when` | **SATISFECHO** | `WhenGateDsl.kt`: `whenAll` / `whenAny` / `whenNot` sobre `WhenPredicate`, tipado y no stringly |
| S2.2 | `post` | **SATISFECHO** | `StageScopeBuilders.kt:221`, `fun post(block: PostScope.() -> Unit)` |

**Consecuencia:** S3–S8 siguen siendo trabajo real, pero **ejecutar S0–S2 otra vez
destruiría trabajo ya certificado**. El paquete debe marcarse, no reejecutarse.

**Límite explícito de esta tabla:** se verificó por presencia de símbolos y por
comportamiento en el gate. No se verificó cobertura de cada sub-ítem ni que las
invariantes que S0–S4 enuncian estén todas ellas. La fila S0.4 `PARCIAL` significa
exactamente eso: hay witnesses, no hay la suite que el paquete imaginó. Cualquier
`SATISFECHO` de esta tabla es «la superficie existe y se ejercita», no «la propiedad
está probada al nivel que el paquete pide».

## 2. SecuenciaRP-7 que se inserta

```text
RP7-SEM   Convergencia semántica
  ├─ S3  agent / environment / options        → 02-directive-model.md
  ├─ S4  Scripted Runtime v2                  → 03-scripted-runtime.md
  ├─ S5  Reactive Event Spine v2              → 04-events-reactivity.md
  ├─ S6  Plugin SDK v2                        → 05-step-plugin-sdk-v2.md
  ├─ S7  Certification Harness v2             → 06-certification-harness-v2.md
  └─ S8  Migración / compatibilidad           → 08-roadmap.md, 11-release-cut.md

  transversales: 01-semantic-constitution.md, 10-ADRs.md, 07-AGENTS-patch.md

RP7-ASX   Agent-first / secretless            → docs/proposals/pipelinek-agent-secretless/
  ├─ ASX-0  baseline de compatibilidad
  ├─ ASX-1  credential capability kernel
  ├─ ASX-2  credential scopes
  ├─ ASX-3  providers
  ├─ ASX-4  tool projections
  ├─ ASX-5  inline CLI
  ├─ ASX-6  MCP / skills
  └─ ASX-7  certificación

RP7-LOCAL  sandbox, límites de recurso, policy, storage, provenance
RP8        controller / worker remoto
RP9        Jenkins / Kubernetes
```

`RP7-SEM` va **antes** que `RP7-ASX`: ASX-0 congela `core.sh`, `withCredentials`,
fingerprints, replay, events y payload *antes* de tocar secretos, y ese freeze sólo
significa algo si la superficie semántica está estable.

`http.request` es el **reference plugin** de S6 y el primer caso real de S7: ya
demostró el seam (contribuidor `ServiceLoader`, sin rama de compilador) y ya recoge
la disciplina de certificación que S7 debe industrializar.

## 3. Lo que esta WU decide

1. Los paquetes `pipelinek-semantic-evolution` y `pipelinek-agent-secretless`
   dejan de ser roadmaps paralelos y pasan a **material de especificación asociado**
   a RP-7, secuenciado arriba.
2. S0–S2 se marcan según §1 y **no se reejecutan**.
3. `07-AGENTS-patch.md` se revisa antes de S3 y lo aceptado se traslada al `AGENTS.md`
   real, para que la constitución semántica sea norma y no propuesta.
4. RP-8 y RP-9 conservan su autoridad actual (`ROADMAP.md` §10 y §11) y **no** se
   broaden: RP9 depende de RP8 y ninguno tiene aún el paquete de specs que su
   sección describe.
5. `RP7-LOCAL` (sandbox, resource limits, Cedar/policy, storage, provenance) **no
   tiene paquete canónico**. La descripción de §9 del ROADMAP es su única
   autoridad y no es suficiente para implementar. Abrir un train propio antes de
   S3–S8, o después, es una decisión abierta — se registra como tal, no se decide
   aquí.

## 4. Lo que esta WU NO decide

- No abre WU-094 (`markdown-toolkit-plugin`). Decidido en RP6-CLOSEOUT: propuesta
  sin demanda, y un TBD no es criterio de salida.
- No modifica los cuatro documentos con deriva de CI
  (`ROADMAP.md` §RP-0, `PRODUCTION_READY_UAT_MATRIX.md` UAT-RP-001,
  `DISTRIBUTION_RELEASE_SPEC.md` §5, `CERTIFICATION_PROTOCOL.md` T5). Enumerados en
  `WU093_HTTP_IMPLEMENTATION_RECEIPT.md`; cada uno merece decisión propia.
- No mueve `.agent/scripts/regenerate_step_inventory.py` fuera de `.agent/`, ni
  cierra `PR-ADR-002` de PROPOSED a ACCEPTED.

  **SUPERSEDIDO (2026-10-03, RP7-SEM S3 governance WU).** La primera mitad de
  esta línea ya no aplica: el generador se movió a
  `scripts/regenerate-step-inventory.py`. El motivo era una contradicción y no
  una preferencia de estilo — el script se declara a sí mismo autoridad del
  inventario, mientras `AGENTS.md` clasifica `.agent/` como «proyección humana
  opcional / histórico; NO autoridad». Una autoridad declarada no puede vivir en
  un directorio declarado no autoritativo: ese es exactamente el estado que
  permite que una sesión futura trate una proyección obsoleta como hecho.
  `PR-ADR-002` sigue sin cerrarse.
- No ejecuta las mutaciones M-http-4, 6, 7, 8, 11, 12, 13, 15, 16.

## 5. Primer paso ejecutable

Abrir `RP7-SEM-0`: **integrar el constitution patch**, es decir revisar
`docs/pipelinek-semantic-evolution/07-AGENTS-patch.md` y trasladar al `AGENTS.md` las
leyes aceptadas (`DECLARATIVE_DIRECTIVE`, `ATOMIC_STEP`, `BLOCK_STEP`, `PURE_BUILDER`,
`SCRIPTED_RUNTIME_CALL`, y `EXPLICIT_CARRIER` / `PURE_DESUGAR` / `FAIL_CLOSED`). Es
documentación normativa, no código, y es lo que evita que S3–S8 se implementen contra
una constitución que sólo existe como propuesta.
