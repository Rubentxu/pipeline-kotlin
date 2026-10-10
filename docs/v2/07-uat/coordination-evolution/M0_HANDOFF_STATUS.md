# WIP-5 — Estado de handoff M0: PK_PREPARED, par usa v0.48.0-rc2 (sujeto a Fabric)

**Fecha:** 2026-10-10
**Hito:** M0 (evolución coordinada PK × Fabric)
**Estado del lado PK:** **`PK_PREPARED`** (canónico, decisión del Pair Integrator).
**Artefacto del par:** `v0.48.0-rc2` (publicado, SHA-256 `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`).
**Sujeto a verificación por Fabric.** PK **no** publica otro release; PK **no**
espera. Puede seguir con trabajo autónomo no dependiente de esta iniciativa.

## Lo entregado por el agente PK

| WIP | Receta | Estado |
|---|---|---|
| WIP-0 | Revalidar baseline | ✅ `M0_BASELINE_REVALIDATION.md` |
| WIP-1 + WIP-2 | Medir ABI publicado y capabilities | ✅ `M0_ABI_INVENTORY.md` (6 contratos EXPERIMENTAL, 1180 entidades públicas, 9 breaking changes registrados) |
| WIP-3 | `PK_CONTRACT_HANDOFF.md` para Fabric | ✅ `handoff/PK_CONTRACT_HANDOFF.md` |
| WIP-4 | `pair_gate_selftest.py` verde | ✅ `M0_SELFTEST.log` (`PASS: shared contract equality; drift fail-closed; incomplete uncertified pair fail-closed`) |
| WIP-5 | Apuntar handoff + bloqueo | ✅ este documento |

## Lo que NO se ha hecho (lo hace el lado Fabric o el Pair Integrator)

| Tarea | Quién | Bloqueo actual |
|---|---|---|
| `FAB_CONSUMER_VERDICT.md` con rangos de compat, tests cruzados, divergencias | **Fabric Consumer Agent** | el repo `Rubentxu/pipelinek-fabric` está en `e6e4fd3` (medido el 2026-10-10) y no se ha tocado desde entonces; no hay consumer agent corriendo este ciclo |
| `PAIR_RECEIPT.json` con `PAIR_CERTIFIED` para M0 | **Pair Integrator** (manual o tercer agente) | requiere el `FAB_CONSUMER_VERDICT.md` previo; requiere `git ls-remote` sobre `Rubentxu/pipelinek-fabric` |
| `verify_pair_gate.py --strict-remote` exit 0 con `M0-<id> PASS; verified N mandatory log hashes; next milestone UNLOCKED` | **Pair Integrator** | requiere `pk_repo` y `fabric_repo` checkouteados localmente; no aplica en este workspace PK |
| Certificación final y promoción del par M0 | **Pair Integrator** (en ambos roadmaps) | requiere los pasos anteriores |

> **Decisión del Pair Integrator (2026-10-10):** PK **no** publica otro release;
> PK **no** espera. El par M0 usa el artefacto publicado `v0.48.0-rc2` sujeto a
> verificación por Fabric. PK puede continuar con trabajo autónomo no
> dependiente de esta iniciativa. El lado PK de M0 está **cerrado**.

## Por qué M0 no se cierra aquí

CRIC-1 §"Regla de bloqueo" y `coordination/PAIR_RELEASE_FLOW.md` §"Estados
válidos del gate" lo dicen explícitamente: la promoción del par exige un único
`PAIR_RECEIPT.json` con `state=PAIR_CERTIFIED`, **no** un estado narrativo
divergente. PK entrega el handoff y mide lo que le toca; el certificado del par
lo emite el Pair Integrator tras cruzar ambos lados.

`agent/PIPELINEK-AGENT-HANDOFF.md` lo refuerza: "No marcar M(n) DONE hasta que
Pair Integrator certifique ambas releases juntas y autorice M(n+1)."

## Acciones del lado PK ya tomadas para acelerar M0

- HEAD PK local = `origin/main` (sin commits sin pushear), `65f97430`.
- Tag `v0.48.0-rc2` publicado y ancestro de `origin/main`.
- Prerelease en GitHub con asset re-descargado y SHA-256 verificado.
- `INTERFACE_CONTRACT.md` SHA-256 coincide con `CONTRACT_SHA256.txt` (CRIC-1
  intacto, no hay drift).
- 6 contratos publicados catalogados y medidos (M0_ABI_INVENTORY.md).
- `PK_CONTRACT_HANDOFF.md` con SHA del artifact, coordinates Maven, capability
  set, lectura N×N, fallback tipado, criterios de aceptación.

## Lo que la handoff documenta y el consumer debe verificar

- Que el artifact PK se resuelve desde el release remoto, no desde `mavenLocal`
  ni del worktree.
- Que el SHA-256 del artifact descargado coincide con el declarado
  (`0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`).
- Que la compilación del adapter de Fabric es contra el artifact publicado,
  no contra el árbol de fuentes.
- Que las capabilities que CRIC-1 §6 lista están disponibles en la release:
  - ✅ `output.read.v1` (`OutputReadPort.committedExtent`, `read`, `readRange`)
  - ✅ `output.tail.v1` (`OutputTailPort.tailState` + `OutputTailState` sealed)
  - ✅ `event.read.v1` (`EventStore.readRecords`, `readSlice`)
  - ⚠ `output.frame-index.v1` parcial (`OutputFrameIndex` público, semántica
    completa de M3)
  - ⚠ `output.retention.v1` parcial (`OutputRetentionPort` + `RetainUntil`,
    policy engine de M5)
  - ❌ `output.wakeup.v1` no público (M1)

## Reanudación

Cuando el `FAB_CONSUMER_VERDICT.md` llegue al Pair Integrator, este:
1. Verifica que el `INTERFACE_CONTRACT.md` siga byte-idéntico en ambos paquetes
   (`sha256sum coordination/INTERFACE_CONTRACT.md` en PK y Fabric).
2. Ejecuta `verify_pair_gate.py --strict-remote` con los clones PK y Fabric
   y el `PAIR_RECEIPT.json` firmado.
3. Si exit 0, declara `PAIR_CERTIFIED` para M0 y refresca el `ROADMAP.md` PK
   con la fila correspondiente.
4. Si exit ≠ 0, emite `BLOCKED_RECEIPT.md` con motivo y la acción correctiva.

PK queda en espera. No hay más trabajo PK para M0; el lado PK está hecho.

## Próximo paso

WIP-13: refrescar `docs/v2/05-roadmap/ROADMAP.md` con la fila de la iniciativa
de evolución coordinada M0–M7, una vez que el Pair Integrator emita
`PAIR_CERTIFIED` para M0. Hasta entonces, la fila queda como `BLOQUEADO — espera
de FAB_CONSUMER_VERDICT.md + PAIR_RECEIPT.json firmado`.
