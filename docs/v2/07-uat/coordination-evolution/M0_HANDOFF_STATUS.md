# WIP-5 — Estado del lado PK en M0: PK_PREPARED, no espera

**Fecha:** 2026-10-10
**Hito:** M0 (evolución coordinada PK × Fabric)
**Estado del lado PK:** **`PK_PREPARED`** (canónico, decisión del Pair Integrator).
**Artefacto del par:** `v0.48.0-rc2` (publicado, SHA-256 `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`).
**Sujeto a verificación por Fabric.** PK **no** publica otro release; PK **no**
espera. Puede seguir con trabajo autónomo no dependiente de esta iniciativa.

## Regla de la que se deriva este estado

`coordination/PAIR_RELEASE_FLOW.md` lo dice explícitamente: la ausencia de
`PAIR_CERTIFIED` **bloquea la promoción del siguiente hito y el arranque integrado
del siguiente**, **no** la implementación, las pruebas ni la publicación de la
candidata del hito actual. Cada agente ejecuta su trabajo sobre su propio
repositorio; el Pair Integrator ata el nudo después.

`Para M0 no existe un certificado anterior`: el verificador exime M0 de
`--previous-receipt` (`verify_pair_gate.py` línea 130: `if mid == 'M0': ensure(
previous_receipt is None ...)`). Fabric hace su M0 y publica su propia candidata;
el Pair Integrator la une después con la PK.

## Lo entregado por el agente PK

| WIP | Receta | Estado |
|---|---|---|
| WIP-0 | Revalidar baseline | ✅ `M0_BASELINE_REVALIDATION.md` |
| WIP-1 + WIP-2 | Medir ABI publicado y capabilities | ✅ `M0_ABI_INVENTORY.md` (6 contratos EXPERIMENTAL, 1180 entidades públicas, 9 breaking changes registrados) |
| WIP-3 | `PK_CONTRACT_HANDOFF.md` para Fabric | ✅ `handoff/PK_CONTRACT_HANDOFF.md` |
| WIP-4 | `pair_gate_selftest.py` verde | ✅ `M0_SELFTEST.log` (`PASS: shared contract equality; drift fail-closed; incomplete uncertified pair fail-closed`) |
| WIP-5 | Apuntar handoff + bloqueo | ✅ este documento |

## Lo que hace cada rol en M0 (ninguno espera a otro)

| Rol | Qué hace | Estado |
|---|---|---|
| **PK Producer Agent** (este lado) | Implementa, prueba, publica su candidata, emite `PK_CONTRACT_HANDOFF.md` | **Hecho.** `v0.48.0-rc2` publicado, SHA-256 `0fd6aec2…` |
| **Fabric Consumer Agent** | Implementa, prueba, publica su candidata, emite `FAB_CONSUMER_VERDICT.md` | **Pendiente.** Repo Fabric en `e6e4fd3` (medido el 2026-10-10). Ningún recibo cruzado lo bloquea: el verificador exime M0 de `--previous-receipt` |
| **Pair Integrator** | Verifica que el `INTERFACE_CONTRACT.md` sea byte-idéntico en ambos paquetes, ejecuta `verify_pair_gate.py --strict-remote` con el `PAIR_RECEIPT.json` firmado por las dos partes | **Pendiente.** No espera: ata el nudo cuando AMBOS lados han publicado |

`coordination/PAIR_RELEASE_FLOW.md` §"Seguridad del orden (evitar interbloqueo)"
lo dice: **"Desarrollo paralelo permitido bajo dos worktrees y artifacts candidate
inmutables, con contrato congelado. Promoción bloqueada hasta aprobación cruzada.
No exigir que ambas publicaciones sucedan de forma atómica en Git."**

La instrucción correcta para M0 es:

- **PK**: ejecuta su M0, publica su candidata, emite su handoff. **No espera.**
- **Fabric**: ejecuta su M0, publica su candidata, emite su verdict. **No espera.**
- **Pair Integrator**: ejecuta su gate cuando ambos lados han publicado, produce
  `PAIR_CERTIFIED`. **No espera** (lo dispara cualquier humano/agente que vea
  ambos candidatos publicados).
- **`PAIR_RECEIPT.json` con `PAIR_CERTIFIED` para M0** solo puede existir DESPUÉS
  de que los dos agentes hayan ejecutado su M0. Nadie debe esperar un recibo que
  solo puede producirse después de ejecutar su propio trabajo.

## Acciones del lado PK ya tomadas para M0

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

El Pair Integrator, cuando vea `v0.48.0-rc2` publicado en PK y la candidata de
Fabric publicada, ejecuta `verify_pair_gate.py --strict-remote` con el
`PAIR_RECEIPT.json` firmado por ambos agentes. Si exit 0, declara
`PAIR_CERTIFIED` para M0. **No hay nadie esperando** a que esto pase para
trabajar: PK ya hizo su parte; Fabric hace la suya; el Pair Integrator ata el
nudo.

## Próximo paso

WIP-13: refrescar `docs/v2/05-roadmap/ROADMAP.md` con la fila de la iniciativa
de evolución coordinada M0–M7, **sin esperar** a `PAIR_CERTIFIED` (la fila
refleja el estado del lado PK como `PK_PREPARED`; cuando llegue
`PAIR_CERTIFIED` se actualiza a `M0_CERTIFIED` y se autoriza M1 como trabajo
integrado). Hasta entonces, el lado PK de M0 está **cerrado** y PK puede
seguir con trabajo autónomo no dependiente de esta iniciativa.
