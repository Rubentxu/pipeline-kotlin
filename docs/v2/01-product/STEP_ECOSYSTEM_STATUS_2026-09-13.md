# STEP ECOSYSTEM STATUS — snapshot 2026-09-13 (post S2-A7/G8 deleteDir closure)

Companion to `STEP_ECOSYSTEM_MATRIX.md` (which still shows the stale E0 snapshot
"CERTIFIED: 3" — see note in S2_A7_CORE_DELETEDIR_G8_CERTIFICATION_RECEIPT.md).
This is the authoritative point-in-time inventory.

## Recuento global

```text
CERTIFIED:                 9  (8 core + example.uppercase external reference)
IMPLEMENTED_UNCERTIFIED:  ~10 (9 block steps working + waitUntil registry stub)
LEGACY executable:         5  (milestone, cleanWs, load, waitUntil, archiveArtifacts)
NOT_STARTED:             ~60 (plugin families E2-E10 + SCM + readFile/fileExists + decorators)
DEFERRED_REMOTE:           3  (build(job:), waitForBuild, properties)
```

## LFC-2E0 — families status

| Family | Steps | Status |
|---|---|---|
| E0-A primitives | error, sleep | 2/2 CERTIFIED |
| E0-B control (block) | retry, timeout, catchError, warnError, unstable, parallel | 6 working, IMPLEMENTED_UNCERTIFIED; formal certification needs ADR-0081 BodyInvoker |
| E0-C execution context (block) | dir, withEnv, withCredentials | 3 working, IMPLEMENTED_UNCERTIFIED (CTX-P) |
| E0-D workspace/files | writeFile CERT, deleteDir CERT, readFile/fileExists NOT_STARTED (LFC-2R2 blocked), cleanWs/archiveArtifacts in burn-down prep | 2/6 |
| E0-E runtime utilities | isUnix CERT; pwd registry (G7 BLOCKED LFC-2R2); milestone G4 local-ready; waitUntil/load pending; timestamps/ansiColor NOT_STARTED | 1/7 |
| E0-F credentials+SCM | withCredentials working (block); git/checkout NOT_STARTED plugin candidates | 0/3 |

## Core burn-down (LEGACY_PLUGIN_IDS 12 keys)

```text
Burned:   8/12 (echo, sh, error, sleep, writeFile, emit.event, isUnix, deleteDir)
Borrado físico legacy: los 8 + pwd (G5)
Registry-but-uncertified: pwd (LFC-2R2), waitUntil (stub), milestone (G4 local @ 87d998ff)
Legacy vivo (5): milestone, cleanWs, load, waitUntil, archiveArtifacts
Preparación local sin push: cleanWs G1-G3 (bdd4f81e), archiveArtifacts G0+G1 (291cb98d)
```

## Orden de ataque acordado

1. milestone G4 merge → G5 (destructivo, GO separado) → G6/G7/G8
2. cleanWs G4→G8 (gemelo de deleteDir)
3. archiveArtifacts G2→G8 (3 deltas G2 + 7 pins S3 rojos pre-existentes documentados)
4. waitUntil: desbloqueo vía ADR-0081 BodyInvoker (draft local @ e2f24f04)
5. load: según SPIKE-018 (boundary ScriptLoader/PipelineCompiler)

## Plugin families (E2-E10) — all NOT_STARTED

Llave de arranque: example.uppercase CERTIFIED ya probó packaging → discovery →
certificación con zero core edits. Primeras por prioridad: E3 junit (P0, specs
listas), E0-F git/checkout (P0), E2 utilities (P1).
LFC-2R2 (structured runtime-return, ADR local @ 5128ae93) desbloquea de golpe
readFile/fileExists y el cierre G7 de pwd.
