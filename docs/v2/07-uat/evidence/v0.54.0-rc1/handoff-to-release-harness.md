# Handoff a `pipelinek-release-harness` — v0.54.0-rc1

**Fecha:** 2026-10-12
**Candidata:** v0.54.0-rc1 (train `0.54.0`, sequence 1) — **abre el train 0.54.0**
**Tag:** `v0.54.0-rc1` (anotado, peel = HEAD al construir)
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable la hace el harness, no el repositorio de producto).

## Capacidades publicadas por esta candidata

Las **once** capacidades negociables del chain CRIC-1 quedan declaradas en
`INTERFACE_CONTRACT.md` §6: M1 (2 PUBLICADA) + M2 (3 EXPERIMENTAL) + M3 (3
EXPERIMENTAL) + M6 (2 EXPERIMENTAL) + **M7 (1 EXPERIMENTAL nuevo en esta
release: `runtime.eventshape.v1`)**. Los consumidores deben actualizar su
rama de negociación para reconocer la nueva capacidad M7 como ofrecida
en `v0.54.0-rc1+`.

| Capability ID | Publicada en | Estado | Test certificador |
|---|---|---|---|
| `output.follow.v1` | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** | `SegmentOutputFollowerTest` (18 casos) + `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003) |
| `events.follow.v1` | `v0.49.0-rc1` (2026-10-10) | **PUBLICADA** | `EventFollowerAdapterTest` (18 casos) + `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006) |
| `runtime.inspect.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeIntrospectionPortAdapterTest` (8 casos) |
| `runtime.cancel.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeControlPortAdapterTest` (8 casos) |
| `runtime.recover.v1` | `v0.50.0-rc1` (2026-10-10) | **EXPERIMENTAL** | `RuntimeRecoverPortAdapterTest` (8 casos) + `RuntimeRecoverDecisionTableFitnessTest` (12 casos) |
| `output.read.digested.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `OutputReadDigestedAdapterTest` (5 casos) |
| `output.pin.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `OutputPinPortAdapterTest` (10 casos) |
| `output.refusal.retention.v1` | `v0.51.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `PruneAuthorisationAdapterTest` (5) + `OutputRefusalClosedTest` (4) + `RecoverRefusalPinExtensionTest` (3) |
| `runtime.structure.v1` | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `RuntimeIntrospectionPortStructureAdapterTest` (8) + `RuntimeIntrospectionPortStructurePaginationTest` (5) |
| `runtime.operation.v1` | `v0.53.0-rc1` (2026-10-11) | **EXPERIMENTAL** | `RuntimeIntrospectionPortOperationAdapterTest` (6 casos) |
| `runtime.eventshape.v1` | `v0.54.0-rc1` (2026-10-12) | **EXPERIMENTAL** | `EventShapeReadPortAdapterTest` (10 casos, `:pipeline-events`) + `IntrospectionUnknownOperationRefusalTest` (3 casos) |

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.54.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `a09ef52ccb00beb361083c3e6d72637bb49bb2faffed8edfc980563f4e11a356`.
- **5 SDK coordinates** a `0.54.0`:
  - `pipeline-domain-0.54.0.{jar,pom,module}`
  - `pipeline-events-0.54.0.{jar,pom,module}` (M7 surface aquí)
  - `pipeline-output-0.54.0.{jar,pom,module}`
  - `pipeline-runtime-0.54.0.{jar,pom,module}` (M6 surface)
  - `pipeline-scripting-api-0.54.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.54.0.{pom}`.
- **`candidate_id`:** `sha256:a09ef52ccb00beb361083c3e6d72637bb49bb2faffed8edfc980563f4e11a356`.

## Cambios sobre la candidata anterior (v0.53.0-rc1 → v0.54.0-rc1)

1. **M6 I.6 PARTIAL closure** — `EventShapeReadPort` en `:pipeline-events`
   permite agrupación dirigida por eventos en `runStructure`.
2. **`IntrospectionRefusal.UnknownOperation`** — caso aditivo (B.2).
3. **`DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L`** — budget constant (B.1).
4. **5 casos del sealed `TypedEvent`** — pineados por test 9 (B.3).
5. **`CapabilityRegistrationTest` 6 → 7 casos** — pine CRIC-M7.
6. **`INTERFACE_CONTRACT.md`** — §6 + tabla "Capacidades publicadas (CRIC-M7)".
7. **`CONTRACT_SHA256.txt`** — `87e8ac99...` → `e225ef4ddc...`.
8. **Versión** `0.53.0` → `0.54.0`.

## Batería de certificación (M7)

- `EventShapeReadPortAdapterTest` — 10 casos UAT-PK-M7-001 (verdes)
- `IntrospectionUnknownOperationRefusalTest` — 3 casos UAT-PK-M7-002 (verdes)
- `RuntimeIntrospectionPortStructureAdapterTest` (M7 row) — 3 casos UAT-PK-M7-003 (verdes); 8 M6 rows preservados
- `CapabilityRegistrationTest` — 7 casos (was 6), pine SHA + 11 capacidades + 15 test classes + tabla CRIC-M7

**Subtotal M7:** 16 casos certificadores nuevos. Baterías M1 (42 casos), M2 (36 casos), M3 (27 casos), M6 (19 casos) — todas verdes sin regresiones.

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado.** El harness NO
   reutiliza una imagen previa identificada solo por nombre. El
   `candidate_id` (sha256 del ZIP) es la identidad inmutable.
2. **Correr la batería de certificación M7** (los 16 casos
   certificadores arriba) sobre la imagen reconstruida.
3. **Verificar el `CONTRACT_SHA256.txt`**:
   `e225ef4ddcb95bccb5e97da1c39a585df743809952509c2949a5161b182cb349`
   debe coincidir con el SHA-256 del `INTERFACE_CONTRACT.md` de la
   imagen.
4. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED, las
   once capacidades pueden promocionarse a PUBLICADA en una release
   posterior (la promoción es un cambio de tabla, no un cambio de
   binario).
5. **Si es BLOCKED:** añadir el detalle al final del
   `release-receipt.md` como "Resultado de la certificación" y emitir
   un nuevo handoff con la candidata correctiva.

## Estado final de la cadena CRIC-1

```
M1..M3: 8 capacidades (2 PUBLICADA + 6 EXPERIMENTAL)
M4..M5: compat handoff only
M6: 2 EXPERIMENTAL (M6 I.6 PARTIAL closed in M7)
M7: 1 EXPERIMENTAL (PARTIAL closed)
TOTAL: 11 capacidades, 0 PARTIAL rows
```

**Ready for `PAIR_CERTIFIED` con Fabric** — la candidata M7 cierra la
cadena CRIC-1. Tras la certificación por el harness, el
`PAIR_CERTIFIED` con Fabric (M7 release contra el artefacto
`v0.54.0-rc1`) cierra el ciclo.

## Pendiente

- **Tag + Prerelease en GitHub:** la Prerelease `v0.54.0-rc1` se
  publica desde la línea de comandos después de este handoff. El ZIP
  `pipelinek-0.54.0-rc1` se sube como asset; el SHA-256 se anota en
  la descripción de la Prerelease.
- **Veredicto del harness:** pendiente. Se añadirá al
  `release-receipt.md` cuando llegue.
- **M6.4 follow-up (consumer):** el `example-external-consumer` en el
  worktree principal necesita ser extendido con el M7 surface. No
  bloquea la candidata M7; ocurre después de la publicación.
- **Promoción EXPERIMENTAL → PUBLICADA:** solo tras CERTIFIED, en un
  release posterior.
- **`PAIR_CERTIFIED` con Fabric:** tras el veredicto del harness, el
  Integrator acepta el par contra el artefacto `v0.54.0-rc1`.

---

**Firma:** PK release maintainer (autoridad del release-receipt M7 vigente).
