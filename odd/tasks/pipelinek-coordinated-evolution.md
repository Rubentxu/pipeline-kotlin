# Feature: PipelineK × Fabric — evolución coordinada M0–M7

**Objetivo:** ejecutar la iniciativa de evolución coordinada entre `Rubentxu/pipeline-kotlin`
y `Rubentxu/pipelinek-fabric` descrita en `docs/pipelinek-coordinated-evolution/README.md`,
arrancando por **M0** (lo único que PK puede levantar sin esperar a Fabric) y dejando
preparada la infraestructura para M1–M7.

**HEAD base observado al 2026-10-10:** PK `e518bc8` (origin/main en sync); tag publicado
`v0.48.0-rc2`; contract `CRIC-1` SHA-256 `0b14b85327303b76eeee4a6dc3f381619edf9a28b1b7cdaa894633b74106d26b`
(verificado contra `CONTRACT_SHA256.txt`). La baseline de Fabric en el package es
`e6e4fd3e191dd483994af89418c93ecd5ffdb3e0`; revalidar antes de cualquier agente.

**Cierre de bloque (este WIP):** **M0 PAIR_CERTIFIED** (o M0 con recibo firmado por el
Pair Integrator que autorice arranque de M1). M0 no cambia código PK; produce el
`PK_CONTRACT_HANDOFF.md` y queda a la espera de `FAB_CONSUMER_VERDICT.md` y del
`PAIR_RECEIPT.json` con `PAIR_CERTIFIED` que autoriza M1.

## Por qué M0 primero

M0 es el único hito donde PK puede trabajar de forma autónoma. Los hitos M1–M7 requieren
`PAIR_CERTIFIED` previo (PAIR_RELEASE_FLOW.md §"Integridad temporal de la secuencia M0 → M7"),
y ese recibo solo puede existir cuando ambos repos han publicado sus artefactos
cruzados. Por construcción, sin `FAB_CONSUMER_VERDICT.md` no hay `PAIR_RECEIPT`; sin
`PAIR_RECEIPT` no se arranca M1. La consecuencia operativa: PK ejecuta M0, deja el
handoff preparado, y queda en espera de Fabric.

## Alcance (sí / no)

**Sí (M0):**
- Revalidar baseline PK (HEAD local vs `origin/main` vs `v0.48.0-rc2`).
- Medir ABI publicado PK (BCV dumps + `published-contract-exceptions.json`).
- Medir capabilities PK (qué read ports / event ports / cursor interfaces son públicos).
- Generar `PK_CONTRACT_HANDOFF.md` desde la plantilla con SHA, ABI real (`javap`/consumer),
  capability set, suites verdes, UAT PK, artifact checksum, limitaciones y versión.
- Ejecutar `pair_gate_selftest.py` para confirmar que el gate está bien cableado.
- Apuntar el handoff al agente Fabric.

**No (fuera de M0):**
- Cambios de código PK (los cambios de M1+ van en commits posteriores, tras PAIR_CERTIFIED).
- Push a `origin/main` por la iniciativa; el push de v0.48.0-rc2 ya se hizo.
- Empezar M1 sin PAIR_CERTIFIED (PAIR_RELEASE_FLOW.md lo prohíbe).
- Reimplementar la documentación de Fabric en PK (el README lo prohíbe).

## Restricciones

- AGENTS.md, ADR-0099, ADR-0105, ADR-0106 vigentes y vinculantes.
- Conventional Commits; el bump de versión va por commit atómico separado.
- `INTERFACE_CONTRACT.md` es bit-idéntico en ambos paquetes (verificación previa a cada agente).
- El gate se ejecuta desde workspace sobre clones, no introduce pipelines en el repo
  (README §"Importación" + GATE_OPERATIONS.md §"Límites del bloqueo").
- Cada bloque exige `git rev-parse HEAD`, `git ls-remote origin refs/heads/main`, último
  tag publicado y SHA del SDK que resuelve un consumidor REAL, con fecha (BASELINE-PINS.md).

## Lista de tareas

| ID | Tarea | Salida verificable | Estado |
|---|---|---|---|
| WIP-0 | Revalidar baseline PK: HEAD local vs origin/main, tag v0.48.0-rc2, contract SHA-256 = `0b14b85...`, INTERFACE_CONTRACT.md idéntico en ambos paquetes | `BASELINE-PINS.md` actualizado a 2026-10-10 con PK `e518bc8` y v0.48.0-rc2 | PENDIENTE |
| WIP-1 | Medir ABI publicado PK: listar 4 contratos publicados + `published-contract-exceptions.json` + BCV dumps | `docs/v2/07-uat/coordination-evolution/M0_ABI_INVENTORY.md` con dump de `javap` y resumen de capabilities | PENDIENTE |
| WIP-2 | Medir capabilities PK: read ports (`OutputReadPort`, `OutputTailPort`), event ports, cursor interface, frame index | `docs/v2/07-uat/coordination-evolution/M0_CAPABILITIES.md` con tabla capability → implementación real | PENDIENTE |
| WIP-3 | Generar `PK_CONTRACT_HANDOFF.md` desde `coordination/PK_CONTRACT_HANDOFF.template.md` con SHA, ABI, capability set, suites, UAT, artifact checksum, limitaciones | `docs/pipelinek-coordinated-evolution/handoff/PK_CONTRACT_HANDOFF.md` listo para el Fabric Consumer Agent | PENDIENTE |
| WIP-4 | Ejecutar `pair_gate_selftest.py` y capturar salida como evidencia de admisión | log `docs/v2/07-uat/coordination-evolution/M0_SELFTEST.log` con `CONTRACT-ONLY PASS` | PENDIENTE |
| WIP-5 | Apuntar el handoff y bloquear a la espera de `FAB_CONSUMER_VERDICT.md` + `PAIR_RECEIPT.json` con `PAIR_CERTIFIED` | nota en `odd/tasks/pipelinek-coordinated-evolution.md` §"Hand-off" | PENDIENTE |
| WIP-6 | **Bloqueado por Fabric:** M1 — OBS-PC seguro live + Event/Output reader desde otra JVM + UTF-8 incremental + ABI/capabilities | UAT-PK-001..008 verdes; ABI publicada; PK release tag remoto | BLOQUEADO (`PAIR_CERTIFIED` previo) |
| WIP-7 | M2 — `inspect/recover/cancel` solo si el seam no existe; sin reconciler distribuido | consumer compila contra PK publicado, recover no duplica efectos | BLOQUEADO |
| WIP-8 | M3 — Retención estable + lecturas por rango + frame metadata; nunca segunda autoridad de replicación | red caída/ACK perdido, replay byte por byte y cancel progresiva | BLOQUEADO |
| WIP-9 | M4 — PK sin adaptación Jenkins; soporte estable de reading | regresión a última PK publicada | BLOQUEADO |
| WIP-10 | M5 — Política pin/release solo si la retención actual no permite conservar hasta ACK | UAT-AR-006/007, AAT-27; release PK si cambia ABI | BLOQUEADO |
| WIP-11 | M6 — Enriquecimiento contextual + presión local solo si imprescindible | UAT-Q-001 + bytes correctos, presión no descarta semántica | BLOQUEADO |
| WIP-12 | M7 — OTel + hardening + seguridad + reproducibilidad; no duplicar EventStore | UAT-SEC-001..008, AAT-37..44; release PK si cambia | BLOQUEADO |
| WIP-13 | ROADMAP refrescado con la fila M0 PAIR_CERTIFIED (cuando se emita) + deuda priorizada + handoff al release harness | `docs/v2/05-roadmap/ROADMAP.md` actualizado | PENDIENTE |

## Criterios de aceptación del bloque M0

- HEAD PK local = origin/main, sin commits sin pushear; v0.48.0-rc2 sigue siendo la candidata
  publicada más reciente en la base.
- INTERFACE_CONTRACT.md SHA-256 verificado en PK (en el package) y declarado en
  CONTRACT_SHA256.txt; si difiere, `BLOCKED` con motivo.
- `PK_CONTRACT_HANDOFF.md` completo y firmado por el agente PK; ABI y capabilities
  declarados y verificables por `javap` o consumer real.
- `pair_gate_selftest.py` termina con `CONTRACT-ONLY PASS` y la salida capturada como
  evidencia de admisión (M0 no exige `--strict-remote`; lo exige `PAIR_RECEIPT.json` con
  `PAIR_CERTIFIED`).
- A la espera de Fabric: el handoff está en la ruta que el `coordination/PAIR_RELEASE_FLOW.md`
  define; el Pair Integrator puede ejecutarlo cuando llegue el `FAB_CONSUMER_VERDICT.md`.

## Loop recomendado por iteración M(n)

1. **Preflight** (no destructivo): revalidar baseline (`git rev-parse HEAD`, `git ls-remote`,
   contract SHA, tag publicado). Bloquea si algo cambió sin actualizar el package.
2. **Trabajo PK específico del M**: isolated si es M0; condicionado a `PAIR_CERTIFIED` previo
   si es M1–M7. Commits Conventional; nada de squash que rompa la historia (ADR-0099).
3. **Hand-off a Fabric**: `PK_CONTRACT_HANDOFF.md` con SHA, ABI, capabilities, suites,
   UAT, artifact checksum, riesgos, matrix N/N-1.
4. **Espera de `FAB_CONSUMER_VERDICT.md`**: cruzar rangos de compat, tests, divergencias.
   Si la adaptación rompe invariantes PK, priorizar compatibilidad o feature-gate con
   fallback tipado; no añadir dependencia Fabric en PK.
5. **Publicación PK** (si PK cambió): release remoto verificable por digest y
   `git ls-remote` (no `mavenLocal`).
6. **Publicación Fabric**: el agente Fabric consume la PK publicada, certifica, integra
   y publica.
7. **Pair Integrator** ejecuta `verify_pair_gate.py --strict-remote` con `--previous-receipt`
   si M ≥ 1; genera `PAIR_RECEIPT.json`; autoriza M(n+1) si todo verde.
8. **Cierre**: refrescar ROADMAP, emitir recibo, archivado en `evidence/M<n>/`.

## Comprobaciones aplicables

- `./gradlew check` (gate local del repo PK) por cada WIP que cambie código.
- `python3 coordination/pair_gate_selftest.py --pk-package <path> --fabric-package <path>`
  antes de declarar cualquier M verde.
- `git ls-remote origin refs/heads/main refs/tags/<tag>` antes de cada push.
- `sha256sum` sobre `INTERFACE_CONTRACT.md` antes y después de cada agente.

## Hand-off (estado al cierre de M0)

- `PK_CONTRACT_HANDOFF.md`: ruta local en `docs/pipelinek-coordinated-evolution/handoff/`.
- `FAB_CONSUMER_VERDICT.md`: **esperando** al agente Fabric.
- `PAIR_RECEIPT.json` (M0): **no existe todavía**. El Pair Integrator lo emite cuando
  Fabric devuelva su verdict y ambos artefactos queden publicados y verificados por
  `verify_pair_gate.py --strict-remote`.

## Próximo paso inmediato

WIP-0: revalidar baseline (HEAD local, `origin/main`, tag `v0.48.0-rc2`, contract SHA-256
`0b14b853...`, INTERFACE_CONTRACT.md en ambos paquetes). Si la contract SHA difiere de
`CONTRACT_SHA256.txt` ⇒ `BLOCKED` con motivo (drift de coordinación).

## Archivos relevantes

- `docs/pipelinek-coordinated-evolution/README.md` — punto de entrada y orden de lectura.
- `docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md` — contrato
  vinculante CRIC-1.
- `docs/pipelinek-coordinated-evolution/coordination/PAIR_RELEASE_FLOW.md` — protocolo de
  dos fases y publicación secuencial.
- `docs/pipelinek-coordinated-evolution/coordination/MILESTONE_MATRIX.md` — matriz cruzada
  M0–M7.
- `docs/pipelinek-coordinated-evolution/coordination/verify_pair_gate.py` — verificador
  de admisión (read-only, stdlib only).
- `docs/pipelinek-coordinated-evolution/agent/PIPELINEK-AGENT-HANDOFF.md` — responsabilidades
  del agente PK.
- `docs/pipelinek-coordinated-evolution/coordination/PK_CONTRACT_HANDOFF.template.md` —
  plantilla del handoff.
- `docs/v2/05-roadmap/ROADMAP.md` — autoridad única del roadmap PK.
