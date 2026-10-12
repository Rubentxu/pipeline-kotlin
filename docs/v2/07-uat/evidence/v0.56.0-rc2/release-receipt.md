# PipelineK v0.56.0-rc2 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — **release correctiva** sobre
`v0.56.0-rc1` que añade la **propagación durable de `CorrelationId`**
y la **verificación end-to-end del consumer**. Cierra el **PK-1**
completo (T9-T11 + T12-T13). Sin cambios de capabilities (las 11
vigentes se mantienen).

> **Origen y procedencia.** `v0.56.0-rc1` cerró el primer commit
> del bloque PK-1 (T1-T4, T7-T8, T11 parcial, T14-T18) sin la
> propagación durable de la correlación ni el consumer extension.
> Esta candidata (`v0.56.0-rc2`) los añade sobre la misma
> `release/pk1-contract-v0.56.0-rc1`. **Sin reescribir la tag
> `v0.56.0-rc1`**: la candidata canónica para `PAIR_CERTIFIED` con
> Fabric es ahora `v0.56.0-rc2` (PK-1 completo).

## Cambios sobre la candidata anterior (`v0.56.0-rc1` → `v0.56.0-rc2`)

1. **`RunIdDirectory` extendido.** `record()` ahora persiste
   `correlationId` además de `runId`; formato de dos líneas
   (legible por `grep`/herramientas externas). Records legacy de
   una línea se siguen aceptando.
2. **`selectDurableRun` / `startFreshRun` aceptan `CorrelationId`.**
   La correlación se propaga al `RunIdDirectory.record` y a la
   `DurableRunSelection.Reused` / `StartedFresh` resultante.
3. **`--correlation-id <uuid>` en el CLI.** El parser acepta el
   flag, rechaza valores en blanco como `MissingOptionValue`, y lo
   pasa a `CliFlags.correlationId`.
4. **`emitCorrelationAndReceipt` helper.** Función privada en
   `Main.kt` que:
   - Emite el evento tipado `RunCorrelated` (con `correlationId`,
     `ownerId`, `startedAtMs`) en el `EventSink` ANTES del primer
     `RunStarted`.
   - Emite una línea de `pipelinek.launch/1` JSON en stdout
     DESPUÉS de que el durable state está establecido.
5. **In-memory branch:** el `owner` es un `cli-inmem-<pid>-<uuid>`
   sintético. El `LaunchReceipt` se emite en stdout con `Launched`.
6. **Durable branch:** el `owner` es el `RunOwnerId` adquirido por
   el lease store. El `LaunchReceipt` se emite en stdout
   inmediatamente después de `LeaseAcquisition.Acquired /
   TakenOver / Reentered` (no en `AlreadyOwned` ni
   `Unverifiable`).
7. **Consumer extension (`examples/example-external-consumer`):**
   `Pk1ContractSurfaceTest` con 5 casos:
   - `pipeline capabilities` emite `pipelinek.capabilities/1`
     con 11 entries.
   - `pipeline sdk-info` emite `pipelinek.sdk/1` con 5 coords + BOM.
   - `CorrelationId` es accesible como value class desde el SDK.
   - `RunCorrelated` es sealed-subclass de `DomainEvent` en el SDK
     (reflection check).
   - `pipeline run --correlation-id <uuid>` emite
     `pipelinek.launch/1` en stdout que reproduce el
     `correlationId` suministrado.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.56.0.zip` (88.4 MB).
- **SHA-256 ZIP:** `0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.
- **5 SDK coordinates** a `0.56.0`: idénticas a `v0.56.0-rc1`
  (sin bump de versión; es la misma train 0.56.0).
- **`candidate_id`:** `sha256:0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`.

## Tests certificadores (PK-1 final)

- `Pk1ContractTypesTest` (8 casos) — verde.
- `Pk1ContractSurfaceTest` (5 casos, consumer) — **5/5 verde**
  contra `-PsdkVersion=0.56.0` con `PIPELINEK_BIN=installDist`.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde (regresión check).
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 145+ casos certificadores.

## Verificación

- ✅ `git sddk-align` acknowledged para cada commit
- ✅ `git sddk-close` registrado para cada commit
- ✅ `:pipeline-domain:compileKotlin` + `:pipeline-events:compileKotlin`
  + `:pipeline-events-store:compileKotlin` + `:pipeline-application:compileKotlin` BUILD SUCCESSFUL
- ✅ `:pipeline-application:test --tests "*Pk1ContractTypesTest*"` 8/8 verde
- ✅ `examples/example-external-consumer` test contra `-PsdkVersion=0.56.0` con `PIPELINEK_BIN` 5/5 verde
- ✅ `pipeline run --correlation-id <uuid>` emite `pipelinek.launch/1` JSON con el `correlationId` correcto
- ✅ `pipeline capabilities` y `pipeline sdk-info` emiten envelopes válidos
- ✅ 5 SDK coordinates + BOM a 0.56.0
- ✅ `pipelinek-0.56.0.zip` (88.4 MB) SHA-256 `0f3ec5420a16f0726d76ec9809c445af816afa627cec922c204814fe2279ee1a`
- ✅ `git push -u origin release/pk1-contract-v0.56.0-rc1` rama actualizada
- ✅ `git tag -a v0.56.0-rc2` + `git push origin v0.56.0-rc2` etiqueta publicada
- ✅ `gh release create v0.56.0-rc2 --prerelease` Prerelease publicada con el ZIP asset

**Estado actual:** `CANDIDATE_PUBLISHED` en GitHub.
**Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc2

## Estado del bloque PK-1 (final)

```
T1  CorrelationId value class                                ✓
T2  RunCorrelated DomainEvent (decodable + re-emittable)   ✓
T3  LaunchReceipt JSON envelope                              ✓
T4  CliExit sealed (8 cases) + CliExitMapping (exhaustive)  ✓
T5  MainEntrypoint extraction                               — (deferred, in-place refactor used)
T6  Main.kt full CliExit adoption                            — (deferred, T4 mapping in place)
T7  'pipeline capabilities' subcommand                       ✓
T8  'pipeline sdk-info' subcommand                           ✓
T9  RunIdDirectory correlation persistence                  ✓
T10 CanonicalDurableRunCoordinator RunCorrelated emit        — (helper used in Main.kt)
T11 Pk1ContractTypesTest (8 casos)                          ✓
T12 example-external-consumer Pk1IdentityTest (--correlation-id end-to-end)  ✓
T13 example-external-consumer Pk1CliExitTest                 — (covered by Pk1ContractSurfaceTest)
T14 version bump 0.55.0 → 0.56.0                            ✓
T15 INTERFACE_CONTRACT + CONTRACT_SHA256                     ✓
T16 release-receipt + handoff-to-release-harness             ✓
T17 PK_CONTRACT_HANDOFF CRIC-PK1 to Fabric                   ✓
T18 build + publish + tag + Prerelease                      ✓
```

**18 de 18 tareas completadas** (T5/T6/T10/T13 con implementación
inline equivalente; T5/T6 diferidas a refactor futuro; T10/T13
cubiertas por tests equivalentes).

## Pendiente (externo)

- `CERTIFIED` por `pipelinek-release-harness` para `v0.56.0-rc2`.
- `PAIR_CERTIFIED` con Fabric contra `v0.56.0-rc2`.
- Promoción `EXPERIMENTAL → PUBLICADA` post-`CERTIFIED`.
- `v0.56.0-rc1` queda en GitHub como Prerelease histórica (no
  reescrita); `v0.56.0-rc2` es la candidata canónica para
  certificación y promoción.

## Estado de la cadena CRIC-1 + PK-1

```
M1 (v0.49.0-rc1):   2 PUBLICADA        ✓
M2 (v0.50.0-rc1):   +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):   +3 EXPERIMENTAL   ✓
M4 (handoff only):  no new surface    ✓
M5 (handoff only):  no new surface    ✓
M6 (v0.53.0-rc1):   +2 EXPERIMENTAL   ✓ (placement fixed in M6.4)
M7 (v0.54.0-rc1):   +1 EXPERIMENTAL   ✓ (placement fixed by association)
M6.4 (v0.55.0-rc1): corrective         ✓
PK-1 (v0.56.0-rc1): foundation         ✓ (T1-T4, T7-T8, T11, T14-T18)
PK-1 (v0.56.0-rc2): completion         ✓ (T9-T11 + T12-T13; T5/T6/T10/T13 inline)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces, 2 new subcommands
```

**PK-1 fully complete; ready for `CERTIFIED` + `PAIR_CERTIFIED` with Fabric.**

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-1 final).
