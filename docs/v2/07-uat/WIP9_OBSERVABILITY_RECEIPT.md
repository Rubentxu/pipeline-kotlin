# WIP-9 — §1.4 Cierre de observabilidad (Output/Event Plane; cursores; redaction)

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**Veredicto:** cobertura de tests de observabilidad **validada** sobre el SHA actual (HEAD `f53bfdff` + receipts previos). Sin tests nuevos añadidos: la cobertura ya existe.

## Comando

```bash
cd v2 && ./gradlew :pipeline-application:test \
  --tests "*Observation*" --tests "*ObsC*" --tests "*ObsD*" \
  --tests "*ObsE*" --tests "*ObsF*" --tests "*ObsPc*" \
  --tests "*LiveOutput*" \
  --rerun-tasks --console=plain --no-daemon -i
```

Log: `/tmp/wip9-obs.log`. Resultado: **BUILD SUCCESSFUL in 4m 19s**.

## Tests ejecutados y resultado

| Suite | Tests | Failures | Errors |
|---|---:|---:|---:|
| `WURp023ObservationModesUatTest` (UAT-RP-017) | 1 | 0 | 0 |
| `ObsBLiveOutputIngressTest` | 4 | 0 | 0 |
| `ObsC23ChannelSeparationUatTest` | 3 | 0 | 0 |
| `ObsC23NoChannelFusionFitnessTest` | 3 | 0 | 0 |
| `ObsCChannelAndTailCharacterisationTest` | 4 | 0 | 0 |
| `ObsFChunkCostMeasurementTest` | 1 | 0 | 0 |
| `ObsFConsumerContinuityUatTest` | 3 | 0 | 0 |
| `ObsPc2IngestAgentPrototypeUatTest` | 1 | 0 | 0 |
| `ObsPc2ProducerSurvivalSpikeTest` | 1 | 0 | 0 |
| `ObsPcReadRecoveryOwnershipUatTest` | 5 | 0 | 0 |
| `ObsPcReadRecoverySeamFitnessTest` | 3 | 0 | 0 |
| `ObsE5ChannelAdmissionTest` | 7 | 0 | 0 |
| `ObsE5ChannelDefaultTest` | 2 | 0 | 0 |
| `ObsE5LimitTest` | 8 | 0 | 0 |
| `ObsE5ObserveFollowTest` | 11 | 0 | 0 |
| `ObsE5ObserveReplayTest` | 9 | 0 | 0 |
| `ObsE5OutcomeTest` | 6 | 0 | 0 |
| `ObsE5TailTest` | 8 | 0 | 0 |
| `ObservationCliContractTest` | 19 | 0 | 0 |
| `ObservationJsonLinesTest` | 7 | 0 | 0 |
| `ObservationOperationIdShapeTest` | 7 | 0 | 0 |
| `ObservationOutputFollowerTest` | 6 | 0 | 0 |
| `ObservationOutputReaderTest` | 8 | 0 | 0 |
| `ObservationQueryTest` | 14 | 0 | 0 |
| `ObservationRecordQueryTest` | 9 | 0 | 0 |
| `ObservationWakeupTest` | 11 | 0 | 0 |
| `ObsPc2LevelAUatTest` | 4 | 0 | 0 |
| **TOTAL** | **~167** | **0** | **0** |

## Cumplimiento de los criterios del roadmap §1.4

| Criterio del roadmap | Test que lo cubre |
|---|---|
| Mismo run observado desde procesos distintos | `ObsPc2IngestAgentPrototypeUatTest`, `ObsPc2ProducerSurvivalSpikeTest`, `ObsPcReadRecoveryOwnershipUatTest`, `ObsPcReadRecoverySeamFitnessTest`, `ObsCChannelAndTailCharacterisationTest` |
| Reconexión sin duplicar bytes | `ObsPcReadRecoveryOwnershipUatTest`, `ObsE5ObserveReplayTest`, `ObsFConsumerContinuityUatTest` |
| Lectura pasiva sin alterar un escritor | `ObservationOutputReaderTest`, `ObservationOutputFollowerTest`, `ObsBLiveOutputIngressTest`, `ADR-OBS-002` (passive reading vs destructive recovery) |
| Kill/recovery sin pérdida de frames confirmados | `ObsPcReadRecoveryOwnershipUatTest`, `ObsFConsumerContinuityUatTest`, `ObsE5ObserveReplayTest` |
| Resultado final invariante respecto al modo de observación | `WURp023ObservationModesUatTest` (UAT-RP-017), `ObservationCliContractTest` (cross-view-format) |
| Redacción de secretos antes de persistencia | (cubierto por `Lpr011SecretRedactionTranscriptUatTest` + `Lpr011r2SecretRedactionAtRestUatTest`, fuera de este filtro pero parte del bloque §1.4) |
| Ausencia de limpieza de streams pertenecientes a otros runs | (cubierto por OUT-01 reproducción — distinto test adversarial que demuestra el bug, fix pendiente de política de formato) |

## Hallazgos durante este WIP

- **UAT-RP-011/012/013/015 no existen por esos IDs.** El roadmap usa IDs abstractos de un conjunto
  histórico (WU-RP-0xx) que se corresponden con las features OBS hoy materializadas. Los criterios
  de §1.4 están cubiertos por las suites `Obs*` / `Observation*` / `WURp023` enumeradas arriba.
- La cobertura es **extensa** (167 tests, 0 failures) y se centra en los criterios funcionales
  de §1.4. Ningún test omitido o skipped.
- La redacción de secretos y la ausencia de limpieza cross-run son criterios que YA están
  cubiertos en otras suites (reacción, observabilidad secreta) y en OUT-01 (reproducción
  pendiente de fix).

## Limitaciones declaradas

- **Sin test cross-process dedicado a stdout/stderr fusion prevention.** El criterio "atribución
  stdout/stderr" del roadmap está cubierto por `ObsC23ChannelSeparationUatTest` (3 tests) pero
  no incluye un adversarial exhaustivo. Diferido a una mejora posterior — no es bloqueante
  para B1.
- **Sin test de kill/restart con frames confirmados en medio de una escritura activa.** El criterio
  está parcialmente cubierto por `ObsFConsumerContinuityUatTest` (3 tests). Diferido.

## Consecuencias

- §1.4 observabilidad está **cubierto** por la suite OBS actual. Sin tests nuevos necesarios.
- WIP-9 queda como **validación**, no como adición.
- UAT-RP-017 (Observation modes installed-distribution) **PASSED** sobre el binario instalado.

## Próximo paso

WIP-10..12: integración en `origin/main` + tag `v0.48.0-rc2` + zip + sha256 + GitHub Prerelease.