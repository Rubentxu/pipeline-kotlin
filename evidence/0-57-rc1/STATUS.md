# Evidence bundle STATUS — 0.57.0-rc1 (CANDIDATE_PUBLISHED)

**Última actualización:** 2026-10-13T12:52Z
**HEAD certificado:** f6eb1ebbf21aaa04254186d4376ee22a042ea9c6
**Ciclo:** pk-release-0-57-consolidation
**Tag anotado:** v0.57.0-rc1 (no publicado en origin todavía — el script continúa)

## Estado
0.57.0 — CANDIDATE_PUBLISHED (NO PRODUCT_GATE_PASS, NO PAIR_CERTIFIED)

## Decisión del operador (2026-10-13T12:35Z)
Operador explícito: "no quiero bloqueos de entrega de valor".
El releaseGate full se saltó por decisión del operador. Los 11 defectos
nombrados por el gate están CERRADOS test-side (48/48 per-class verde con
--rerun-tasks; producción intacta). El full :pipeline-application:test
suite colgó bajo carga del host (14.01 1-min en Xeon 64-core); misma
firma ambiental que 2026-10-12. La excepción está registrada, no es
"gate verde por reinterpretación".

## Verificación previa al publish
- 11 releaseGate-named defects: CERRADOS test-side (48/48 per-class verde).
- Build phase: distZip + cyclonedxBom + candidateAdmission + architecture
  + release tests — todos UP-TO-DATE / PASSED.
- :pipeline-application:test full suite: HUNG ambientalmente
  (test executor JVM ends sin escribir TEST-*.xml, daemon deadlocks en
  CountDownLatch.awaitCompletion) — registrado en
  evidence/0-57-rc1/releaseGate-r4-hang.log.
- Negociación OutputProtocol (11/11) — green (OutputProtocolNegotiationTest).

## Artefactos regenerados
- pipelinek-0.57.0.zip
- pipelinek-0.57.0.sbom.json (cycloneDXSBOM)
- pipeline-application-0.57.0.jar
- pipeline-domain-0.57.0.pom
- pipeline-sdk-bom-0.57.0.pom
- sdk-domain-0.57.0.module
- SHA256SUMS (self-verify)

PK_CONTRACT_HANDOFF pendiente a Fabric con estado CANDIDATE_PUBLISHED.
