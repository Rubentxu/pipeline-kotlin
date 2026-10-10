# PK_CONTRACT_HANDOFF — emitido desde PipelineK para M0

**Fecha:** 2026-10-10
**Hito:** M0 (bootstrap de la iniciativa PK × Fabric, sin cambios de código)
**Tipo:** handoff de **proveedor** (no declara release de pareja; eso es trabajo del
Pair Integrator tras `FAB_CONSUMER_VERDICT.md` y `PAIR_RECEIPT.json` firmado).

## Hito y SHA del contrato / PRs enlazados

- Hito: **M0** (`milestone-required-evidence.json::M0` → `uat: [UAT-RP-001, UAT-RP-002]`,
  `aat: [AAT-11, AAT-12]`, `pk_changed: false`).
- Contract: `CRIC-1`, SHA-256 `0b14b85327303b76eeee4a6dc3f381619edf9a28b1b7cdaa894633b74106d26b`
  (verificado contra `coordination/CONTRACT_SHA256.txt`).
- PRs: M0 no exige PR cross-repo (no cambia código PK; espera `FAB_CONSUMER_VERDICT.md`).
  M1 abre los PRs cruzados una vez emitido `PAIR_CERTIFIED` para M0.

## PK repo

- URL: https://github.com/Rubentxu/pipeline-kotlin
- HEAD observado (PK preflight, 2026-10-10): `65f97430accfbeec22b7b05157d57d05563c87d5`
  (`origin/main`, sin commits sin pushear).
- Tag publicado de la candidata M0: `v0.48.0-rc2`, peel `74c5331e2c242658f6b4c9e83f896ce5f8a4fe98`.
  Tag object local/remoto: `4c350231e06b39920dac2a0c0f9eb8eb007d35e3`. Ancestro de
  `origin/main` confirmado por `git merge-base --is-ancestor`.
- Predecesor: `v0.48.0-rc1` (`6e8e86bd`) — publicado en GitHub Prerelease el 2026-10-09.

## Fabric consumer repo

- URL: https://github.com/Rubentxu/pipelinek-fabric
- HEAD baseline (registrado en `coordination/BASELINE-PINS.md` por el plugin GitHub el
  2026-10-10): `e6e4fd3e191dd483994af89418c93ecd5ffdb3e0`.
- **Este agente PK no lo mide.** Re-verificar por el Fabric Consumer Agent antes de
  cada preflight y por el Pair Integrator en `verify_pair_gate.py --strict-remote`.

## SHA commit PK / branch / tag candidato o remoto

- Commit candidato M0: `74c5331e2c242658f6b4c9e83f896ce5f8a4fe98` (peel del tag
  `v0.48.0-rc2`).
- Branch: `main`.
- Tag remoto: `v0.48.0-rc2` (`refs/tags/v0.48.0-rc2` →
  `4c350231e06b39920dac2a0c0f9eb8eb007d35e3`).
- Prerelease en GitHub: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc2,
  publicado 2026-10-10T13:34:54Z. Asset re-descargado y verificado por SHA-256.

## Artifact PK inmutable

- Coordinate (Maven, por `pipeline-release`): `dev.rubentxu.pipeline.v2:pipeline-application:0.48.0-rc2`
  (clasificación EXPERIMENTAL).
- URL de registry remoto: `https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.48.0-rc2/pipelinek-0.48.0-rc2.zip`
- SHA-256: `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`
  (medido dos veces: build local + re-descarga desde el release; idénticos).
- Tamaño: 88 200 000 bytes aprox. Raíz del ZIP: `pipelinek-0.48.0-rc2/`.
- `Implementation-Version` en los jars de pipelinek: `0.48.0-rc2` (verificado con
  `unzip + strings` sobre los jars `api`, `files`, `http`, `runtime`, `scm-git`,
  `utilities`, `junit`).
- sbom: `pipelinek-0.48.0-rc2.sbom.json` (CycloneDX 1.5) — SHA-256
  `08ac282177a5fe952cc08c35a5a64b215022545ee280b3afa27927bbf17e949c`.
- Método de resolución: **descarga directa del release remoto por URL +
  verificación de SHA-256**. NO se usa `mavenLocal`, no se resuelve del worktree,
  no se reconstruye del source tree.

## Delta ABI compilada (javap)

Inventario completo: `docs/v2/07-uat/coordination-evolution/M0_ABI_INVENTORY.md`.

Resumen de entidades públicas por contrato:

| Módulo | Public entities | Madurez |
|---|---:|---|
| `pipeline-domain` | 811 | EXPERIMENTAL |
| `pipeline-events` | 159 | EXPERIMENTAL |
| `pipeline-scripting-api` | 113 | EXPERIMENTAL |
| `pipeline-credentials-api` | 43 | EXPERIMENTAL |
| `pipeline-output` | 44 | EXPERIMENTAL |
| `pipeline-step-sdk:api` | 10 | EXPERIMENTAL |

**Cambios desde `v0.48.0-rc1` (`6e8e86bd`):** ninguno en la superficie publicada
(PK `changed=false` en M0). Los commits 07ecd02 / b3e60c9 / 3f59c57 / 98e25f3 /
0d2ee1a / 74c5331 / 417dc48 / 4c725e4 / e518bc8 / 65f9743 cambian test (B1aSh) +
docs (WIP-11 + B1 + ROADMAP + handoff + coordination-evolution) + un bump de
versión. **Ninguno cambia el BCV dump versionado**, confirmado por
`git diff 6e8e86bd 65f97430 v2/pipeline-*/api/*.api` (vacío).

Breaking changes registrados en
`v2/contract/published-contract-exceptions.json` (P3-E ledger): 9 entradas, todas
anteriores a `v0.48.0-rc2`. **Sin nuevas entradas entre `v0.48.0-rc1` y
`v0.48.0-rc2`.**

## Capabilities / feature ranges negociadas (WorkerHello / manifest)

Materialización del contrato CRIC-1 §6 ("Versionar las capacidades; cambios
incompatibles no pueden pasar por cambios nominales de versión sin test de
consumers"):

- **Output Plane reader (`output.read.v1`):** `OutputReadPort.committedExtent`,
  `OutputReadPort.read`, `OutputReadPort.readRange` — público en
  `pipeline-output:0.48.0-rc2`. ✓ disponible.
- **Output Plane tail (`output.tail.v1`):** `OutputTailPort.tailState` + `OutputTailState`
  sealed (`Open` / `Sealed` / `Unavailable` / `RetentionGap` / `Corrupt`) — público.
  ✓ disponible.
- **Event Plane (`event.read.v1`):** `EventStore.readRecords` + `EventStore.readSlice`
  con `EventCursor` y secuencias estables — público. ✓ disponible.
- **Output Frame Index (`output.frame-index.v1`):** `OutputFrameIndex` está público
  pero la semántica de M3 ("navegación por ordinal sin escanear todo el run") no
  está completamente materializada. ⚠ parcial.
- **Output Retention (`output.retention.v1`):** `OutputRetentionPort` + `RetainUntil` —
  público pero la política de pin/release genérica es de M5. ⚠ parcial.
- **Wakeup coalescible (`output.wakeup.v1`):** NO público. La promesa del contrato
  CRIC-1 §10 ("Registro de wakeup anterior a última lectura, recheck + polling de
  respaldo. Wakeup es hint coalescible; pérdida o doble wakeup no afecta
  corrección") es de M1, no de M0. ❌ NO disponible.
- **WorkerHello / WorkerLease / StreamFraming / ConsoleSource / ProjectionSource:**
  no son parte de PK; son el lado Fabric. CRIC-1 §6 los preserva del release
  model v1 — Fabric los publica y los mantiene, PK solo observa los efectos
  cuando Fabric los usa.

## Identidades / eventos / frames y refusals afectados

- **Identidades (CRIC-1 §1):** `runId`, `operationId`, `attemptId`, `channel`,
  `stream` son los referentes compartidos. Tipos públicos:
  `OutputStreamAddress(runId, operationId, channel)` → `OutputStreamId`,
  `OutputCursor(stream, committedOffset)`. La identidad del canal es
  `OutputChannel` enum (`STDOUT` / `STDERR`).
- **Refusals tipados (CRIC-1 §2 + §3):** `OutputReadResult.Refused` con
  `OutputRefusal` sealed (`DanglingCommit`, `ForeignStream`, `InvalidRange`, etc.).
  Los huecos de replicación se distinguen de EOF (CRIC-1 §8: "`[a,b)` + recepción
  `[b,c)` ⇒ Gap, no ACK acumulado").
- **Eventos (CRIC-1 §3):** `EventStore` con `EventRecordRead`/`EventSlice`,
  `UndecodableReason` para fallos de decodificación tipados. **No** se usa
  EventStore como transporte de stdout ni como autoridad de recovery
  (CRIC-1 §3 + §6: "los DomainEvents no son un sistema de transporte de stdout,
  ni representan el `OperationJournal` crudo").

## Reader / recovery contract y retention hooks

- **Reader (CRIC-1 §2):** `OutputReadPort` lee bytes ya persistidos, con cursor
  durable y páginas acotadas. La lectura no ejecuta recovery destructivo
  (CRIC-1 §5: "Una lectura no ejecuta recuperación destructiva; no relanza
  efectos externos automáticamente"). El read-side usa `storeForReading`, NO
  `storeForWriting` (ADR-OBS-002; ver `OutputPlaneProvider.kt:72-75`).
- **Recovery:** `OperationJournal` es **privado** a PK; no es público, no aparece
  en el BCV dump. CRIC-1 §9: "PipelineK posee recuperación efectiva de sus
  operaciones; Fabric NO … escribe directamente el journal privado de PipelineK."
  El adapter de Fabric usa `inspect/recover/cancel` (M2) sobre los puertos
  públicos, no sobre el journal.
- **Retention hooks:** `OutputRetentionPort` + `RetainUntil` son los puertos de
  pin/release. CRIC-1 §12: "no podar rangos hasta probar que su garantía activa
  se transfirió a una copia durable, y que no existe pin." La política pin/release
  genérica es de M5; hoy PK no expone la policy engine — solo la signatura.

## Fixtures reales + cada log de test, comandos, resultados, digest

Para M0, PK aporta el `pipelinek-0.48.0-rc2.zip` consumible por el TestKit de
Fabric. Las UAT/AAT del par (UAT-RP-001/002, AAT-11/12) son del lado Fabric
según `coordination/milestone-required-evidence.json::M0`; PK los aporta como
**compatibilidad de reader** sobre el artifact publicado.

Suite local PK (gate v2, ya cerrado):
- `./gradlew check --rerun-tasks --console=plain --no-daemon` →
  `BUILD SUCCESSFUL in 31m 3s, 325 actionable tasks executed, 5611 tests,
  0 failures, 0 errors, 144 skipped` (log `/tmp/wip11-full-gate-v2.log`).
- `pipeline-application`: 2811 tests, 0 failed, 0 errors, 123 skipped.
- 144 skipped incluye 2 tests `@Disabled` documentados (OUT-01, OUT-02),
  con condición de habilitación en `SegmentOutputStoreTest.kt`.

Digest del log: `sha256(wip11-full-gate-v2.log)` se captura en
`evidence/v0.48.0-rc2/release-receipt.md` (sección "Gate de distribución
instalada (S0.5)").

## N/N-1 matrix y feature fallback tipado

M0 no exige cambio de PK. La matrix N/N-1 que se solicita es:
- **N × N**: PK `v0.48.0-rc2` × Fabric `e6e4fd3` (sin cambios aún).
- **N × N-1**: PK `v0.48.0-rc2` × Fabric `v0.48.0-rc1` baseline (no publicado en
  Fabric; el repo Fabric todavía está en `main` sin tag de release que
  corresponda). Esta matrix es de M1+.

Feature fallback tipado: para capabilities que CRIC-1 §6 lista y PK no expone
aún (`output.wakeup.v1`, `output.frame-index.v1` completo, `output.retention.v1`
genérico), el refusal tipado ya está disponible (`OutputRefusal` sealed). Un
consumidor que pide `output.wakeup.v1` recibe `UnknownCapability` o un
`Refused` tipado, **nunca** un falso LIVE (CRIC-1 §6).

## Rollback de schema

- **No schema change en M0** (`changed=false`).
- Si Fabric detecta incompatibilidad con `v0.48.0-rc2` durante M1: rollback a
  `v0.48.0-rc1` (release previo que sigue en el registry remoto). No se borra
  ningún tag ni se reversiona el historial.
- Si un tag publicado resulta roto después de M0: `PAIR_RECEIPT.json` de M0
  contiene `rollback_pair` apuntando al último par certificado estable
  (CRIC-1 §"Rollback" y PAIR_RELEASE_FLOW.md §"Rollback": "Data formats
  dual-read; nunca borrar stream/pins. El registro de release pareja mantiene
  un último par certificado estable").

## Petición concreta al consumidor + criterio de aceptación

Petición al **Fabric Consumer Agent**:
1. Tomar el artifact `pipelinek-0.48.0-rc2.zip` desde
   https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.48.0-rc2/pipelinek-0.48.0-rc2.zip
   y verificar SHA-256 = `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`.
   **No** usar `mavenLocal`, no resolver del worktree.
2. Compilar el adapter de Fabric contra `pipeline-output:0.48.0-rc2` y
   `pipeline-events:0.48.0-rc2` (coordinates Maven del artifact PK, no del
   árbol de fuentes).
3. Ejecutar el TestKit de interoperabilidad sobre la PK publicada (no sobre
   el worktree). El test debe pasar contra la release remota, no solo
   localmente.
4. Emitir `FAB_CONSUMER_VERDICT.md` con:
   - Rangos exactos de compat probados.
   - Tests cruzados con SHA-256 del artifact consumido.
   - Divergencias y siguientes capacidades que se necesitan.
5. Una vez emitido el verdict, el **Pair Integrator** ejecuta
   `verify_pair_gate.py --strict-remote --receipt PAIR_RECEIPT.json
   --pk-repo <path> --fabric-repo <path>` con ambos clones checkouteados.
   El verificador exige `--strict-remote` y confirma que el tag remoto de
   PK y de Fabric apuntan a los commits de los releases publicados, que la
   `INTERFACE_CONTRACT.md` sigue byte-idéntica, y que el `PAIR_RECEIPT.json`
   tiene `state=PAIR_CERTIFIED`, los 4 approvals (PK_PROVIDER,
   FAB_CONSUMER, PAIR_INTEGRATOR) y todas las evidencias requeridas para M0
   (UAT-RP-001/002, AAT-11/12, GATE-FAB-FULL-SUITE, GATE-PUBLISHED-CONSUMER,
   GATE-PUBLISHED-PAIR-E2E, GATE-LOCAL-RELEASE-ADMISSION).

Criterio de aceptación: `verify_pair_gate.py` exit 0 con mensaje
`PAIR_CERTIFIED M0-<id> PASS; verified N mandatory log hashes; next milestone
UNLOCKED`.

## Estado

**PREPARED** (lado PK). Pendiente:
- `FAB_CONSUMER_VERDICT.md` (agente Fabric).
- `PAIR_RECEIPT.json` con `PAIR_CERTIFIED` (Pair Integrator).

No declarar release de pareja con este documento; es un handoff de proveedor,
no un certificado de par.
