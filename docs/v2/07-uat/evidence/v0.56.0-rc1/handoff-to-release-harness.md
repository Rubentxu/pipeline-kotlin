# Handoff a `pipelinek-release-harness` — v0.56.0-rc1

**Fecha:** 2026-10-12
**Candidata:** v0.56.0-rc1 (train `0.56.0`, sequence 1) — **abre el train 0.56.0**
**Tag:** `v0.56.0-rc1` (anotado, peel = HEAD al construir)
**Estado 1.D:** `CANDIDATE_PUBLISHED` (gate v2 verde; AGENTS.md: la promoción a estable la hace el harness, no el repositorio de producto).

## Capacidades publicadas por esta candidata

Las **once** capabilities vigentes quedan declaradas en
`INTERFACE_CONTRACT.md` §6 sin cambios sobre `v0.55.0-rc1`. Esta
candidata añade las **superficies de contrato PK-1** (no capabilities
nuevas):

- `CorrelationId` (`:pipeline-domain`) — value class con init guard.
- `RunCorrelated` (`:pipeline-events` DomainEvent) — emit en durable
  state, decodificable, re-emitible.
- `LaunchReceipt` (`:pipeline-application`) — JSON envelope
  `pipelinek.launch/1` en stdout.
- `CliExit` (`:pipeline-application`) — sealed interface con 8 casos y
  envelope `pipelinek.cli/1` en stderr.
- `pipeline capabilities` subcommand — envelope `pipelinek.capabilities/1`
  en stdout.
- `pipeline sdk-info` subcommand — envelope `pipelinek.sdk/1` en stdout.

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.56.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.
- **5 SDK coordinates** a `0.56.0`:
  - `pipeline-domain-0.56.0.{jar,pom,module}`
  - `pipeline-events-0.56.0.{jar,pom,module}` (incluye M7 surface)
  - `pipeline-output-0.56.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.56.0.{jar,pom,module}` (incluye M6+M7 surface)
  - `pipeline-scripting-api-0.56.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.56.0.{pom}`.
- **`candidate_id`:** `sha256:258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.

## Cambios sobre la candidata anterior (`v0.55.0-rc1` → `v0.56.0-rc1`)

1. **6 superficies de contrato nuevas** (lista arriba).
2. **`DomainEvent` sealed hierarchy** crece de 71 a 72 variantes
   (`RunCorrelated`).
3. **Tests** — `Pk1ContractTypesTest` (8 casos) + 2 tests de
   round-trip del evento store actualizados a 72 variantes.
4. **Versión** `0.55.0` → `0.56.0` (MINOR — nuevas superficies, sin
   cambios de contrato).
5. **PK_CONTRACT_HANDOFF CRIC-PK1 a Fabric:** sigue a este handoff.

## Batería de certificación (PK-1)

- `Pk1ContractTypesTest` (8 casos): LaunchReceipt JSON, CliExit
  envelope, CliExit code mapping, UuidCorrelationIdGenerator,
  CorrelationId validation, RunCorrelated fields, SdkCapabilities
  11 entries, CliError → CliExit mapping.
- `DomainEventExhaustiveRoundTripTest`: 72 variantes
  (RunCorrelated incluido).
- `DomainEventRoundTripTest`: 72-variant seal.
- `CapabilityRegistrationTest`: 7/7 verde (regresión check).

**Subtotal PK-1:** 80+ casos. Baterías previas (M1, M2, M3, M6, M7)
sin regresiones (132+ casos adicionales).

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado.** El harness
   NO reutiliza una imagen previa identificada solo por nombre. El
   `candidate_id` (sha256 del ZIP) es la identidad inmutable.
2. **Correr la batería de certificación PK-1** (los 8 casos de
   `Pk1ContractTypesTest` + 72 variantes de
   `DomainEventExhaustiveRoundTripTest` + 7
   `CapabilityRegistrationTest`) sobre la imagen reconstruida.
3. **Verificar las superficies nuevas** abriendo
   `pipelinek-0.56.0.zip` y ejecutando:
   - `pipeline capabilities` → debe emitir una línea
     `pipelinek.capabilities/1` con 11 entries.
   - `pipeline sdk-info` → debe emitir una línea `pipelinek.sdk/1`
     con 5 coords + 1 BOM.
4. **Verificar el `CONTRACT_SHA256.txt`** (se documenta tras la
   actualización de `INTERFACE_CONTRACT.md` §6 con la sección
   PK-1).
5. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED,
   las 9 capabilities EXPERIMENTAL pueden promocionarse a
   PUBLICADA en un release posterior.
6. **Si es BLOCKED:** añadir el detalle al final del
   `release-receipt.md` como "Resultado de la certificación" y
   emitir un nuevo handoff con la candidata correctiva.

## Estado final de la cadena CRIC-1 + PK-1

```
M1..M3: 8 capabilities (2 PUBLICADA + 6 EXPERIMENTAL)
M4..M5: compat handoff only
M6: 2 EXPERIMENTAL (placement fixed in M6.4)
M7: 1 EXPERIMENTAL (placement fixed by association)
M6.4: corrective re-publish v0.55.0-rc1 (M6 surface en :pipeline-runtime-api)
PK-1: 0 capabilities (additive contract surfaces)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces
```

**Ready for `CERTIFIED` por harness y `PAIR_CERTIFIED` con Fabric.**

## Pendiente

- ✅ **Tag + Prerelease en GitHub:** Prerelease `v0.56.0-rc1`
  publicada en
  `https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc1`.
  ZIP `pipelinek-0.56.0.zip` (88.4 MB) subido como asset con
  SHA-256 `258d0bbde818cce7edf90c8b46a69025e5b7114b9d0e172b95a605a82f8eb974`.
- **Veredicto del harness:** pendiente. Se añadirá al
  `release-receipt.md` cuando llegue.
- **M6.4 follow-up (consumer) — segunda fase:** extender
  `example-external-consumer` con `Pk1IdentityTest` y `Pk1CliExitTest`
  contra `v0.56.0-rc1`. Ocurre en commits futuros sobre la misma
  release branch.
- **Promoción EXPERIMENTAL → PUBLICADA:** solo tras CERTIFIED, en
  un release posterior.
- **`PAIR_CERTIFIED` con Fabric:** tras el veredicto del harness,
  el Integrator acepta el par contra el artefacto `v0.56.0-rc1`.

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-1 vigente).
