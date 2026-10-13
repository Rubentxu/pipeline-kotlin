# Contrato operativo entre agentes: PipelineK ↔ Fabric

**Norma de trabajo para ambos agentes.** No es una tercera aplicación ni un roadmap que sustituya al de cada repo. Es la secuencia de integración que permite a los dos trabajar en paralelo y entregar releases compatibles.

## Autoridades

- **Producer — PipelineK:** run interno, intentos/durability, operación/journal, plugins, CLI, Event Plane, Output Plane, SDK, publicación de contract/ABI.
- **Consumer — Fabric:** run externo y mapping tipado a producerRunId, admisión/autorización, control plane, scheduler, workers y lease/fence, replicación/ACK, proyecciones, Jenkins/gRPC/Backstage, archive, queries.
- **Certifier/Pair gate independiente:** versión ABI, tag/commit, hashes del contrato, ZIP, SDK y plugin, compat N/N-1, smoke de binarios reales, rechazo de `NOT_MEASURED`, firma/autoridad de un `PAIR_RECEIPT`. El productor no es su propio certificador externo.

## Parejas e hitos en orden

| Pair | Producer exacto | Consumer exacto | Gate de paso |
|---|---|---|---|
| P0 (inmediato) | PK 0.56.0 estable, SHA real del tag y ZIP | Fabric 0.5.0-R1-alpha.5 | lock verificable, Jenkins local vertical, lectura live + memoria bounded, FAB_CONSUMER_VERDICT |
| P1 | PK 0.56.0 estable | Fabric 0.5.0 local stable | 24/24 Jenkins si el perfil lo exige, instalado desde Releases, PAIR_CERTIFIED |
| P2 | PK 0.57.0-rcN candidato | Fabric consumer smoke en rama aislada | HANDOFF+consumer verdict sobre ZIP RC; no promover par productivo hasta stable |
| P3 | PK 0.57.0 stable | Fabric 0.5.0 + R2 consumer con ABI compatible o refusals | ABI N/N-1, 13 escenarios certifier, pares remotos verificados |
| P4 | PK 0.58.0-rcN/stable con read/recovery, si hace falta | Fabric 0.6.0-R2-alpha.1, luego 0.6.0 | UAT-FW+AAT-05..10, UAT-RP+AAT-11..18 y carrier real |
| P5 | PK 0.58+/0.59 solo si ABI nueva justificada | Fabric 0.7.0-rc1/0.7.0 | Jenkins streaming, archive+retention, UAT-JK/AR, AAT-19..30 |
| P6 | Release PK vigente publicada | Fabric 0.8.0 o posterior | budgets, TLS/auth, 24h soak, Query, Podman/K8s opt-in y seguridad |

No fijar fechas arbitrarias ni convertir 'proposed' en tag existente. Toda versión es objetivo sujeto a comprobar `git ls-remote` antes del corte.

## Handoff obligatorio del productor al consumidor

- `producer_repo`, `release_tag`, `producer_commit_sha` completo, `candidate_id`, `product_version`, `published_sdk_coordinates`, `sdk_bom_sha256`, `distribution_zip_sha256`, artefacto y URL de descarga reales, `abi_diff` y `supported_protocol_ids` canónicos, capabilities, `runId` provenance, OutputRefusal/EventRead refusals, SLO/perf medido, mapa de cambios incompatible y fixtures reproducibles.
- Lista exacta de UAT/AAT obligatorias, PASS/FAIL/SKIP, `execution_environment`, scripts/comandos y códigos de salida. Firma/autoridad de certifier de producer independiente.
- Si sólo es RC, estado `CANDIDATE_PUBLISHED`; el consumidor puede ejecutar candidato, pero no establecer cert `PAIR_CERTIFIED` estable con RC.

## Veredicto del consumidor

- `fabric_repo`, `consumer_commit_sha`, `release_tag`, `pk_artifact_sha256`, `same_sdk_bytes` 4 jars, `same_runtime_manifest_source`, `jenkins_hpi_sha256`, `wire_abi_version`, matrix exacta `(producer version, consumer version)` y compat negativa N-1.
- Tests de ejecución **real**: PK run aún vivo, cursor/reconnect, partial output, dos readers, RUN IDs distintos sin mezcla, crash/lease, failure/unstable/cancel, exactness de bytes por canal, `RunConcluded != ConsoleComplete`; qué se ejecutó, qué se omitió.
- Resultado `ACCEPTED|REFUSED|BLOCKED`, con evidencia y claim de alcance. El consumer no altera el productor para hacer pasar fixtures.

## Pair Integrator — verificación final

Usar `docs/fabric-coordinated-evolution/specifications/FAB-SPEC-10-PAIR-RELEASE-ADMISSION.md`, `docs/fabric-coordinated-evolution/roadmap/FABRIC-ROADMAP.md` y el verificador `coordination/verify_pair_gate.py` definido por el diseño **si está realmente disponible y ejecutable en el checkout**. Nunca registrar una salida inexistente: si el verificador no está, implementar/recuperar el ejecutable como parte de un hito explícito o marcar `NOT_MEASURED`. Verificar `PAIR_RECEIPT.schema.json`, SHA remotos, tags descendientes de main, firmas y resultados de UAT; rechazar `PARTIAL`, `NOT_RUN`, `NOT_MEASURED`, SHA drift o falsa coherencia de versión.

No introducir GitHub Actions para la coordinación si el producto utiliza CI local; enlazar evidencia de CI local a PRs y configurar los estados/restricciones del remoto que la política efectivamente permita. Si rulesets/status checks no están activos, reportar `REMOTE_ENFORCEMENT_NOT_CONFIGURED` y no decir que los PRs están protegidos.

## Regla de comunicación

Cada agente emite una solicitud al otro con: `blocking_consumer_case`, test que falla, contrato esperado, ABI mínima, impact surface, versiones, prioridad y deadline lógico (hito, no fecha). El receptor investiga el código del propio repo, no implementa prototipos especulativos. Las compatibilidades se resuelven en PK con semver/protocolo y Fabric con adaptador versionado sin condición ad hoc de string si existe ADT.

**Estado de referencia:** PK0.56 estable publicada, PK0.57 rama sin certifier final; Fabric main SDK0.51, rama R1 SDK0.56 con SHA de procedencia inválido y sin merge; J3 23/24 reportado; worker remoto real todavía por certificar. Evidencia de repos inspeccionada el 13/10/2026, no resultados re-ejecutados en este entorno.
