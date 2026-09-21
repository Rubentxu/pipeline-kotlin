# Protocolo de certificación verificable — Pipeline Kotlin V2

**Estado:** CONTRATO OPERATIVO para la continuación del roadmap desde 2026-09-21.
**Normativa superior:** ADRs aceptados, STEP_PLUGIN_CERTIFICATION.md (C01..C19), contratos públicos, AGENTS.md. Este documento concreta la ejecución y la evidencia; NO rebaja ningún requisito normativo.

## 1. Qué se certifica

Una certificación identifica una TUPLA inmutable: {repo, commit SHA, build inputs/lock, JDK/OS, artefacto y sha256, perfil soportado, contrato/API/schema, conjunto de UAT y resultados}. Un recibo sin SHA del código o sin artefacto trazable es evidencia provisional, no CERTIFIED_AT_SHA. Un commit posterior a la tupla tiene estado NOT_YET_RECERTIFIED hasta probar su impacto; no se heredan garantías ciegamente.

Estados: DESIGNED → IMPLEMENTED_UNCERTIFIED → CANDIDATE_VERIFIED → CERTIFIED_AT_SHA; BLOCKED y REJECTED como decisiones justificadas; RELEASED_ARTIFACT sólo para los bytes realmente publicados. Nunca describir una UAT omitida/quarantined como PASS. Los nombres de estado de este protocolo son de reporte; las transiciones normativas del SDK siguen STEP_PLUGIN_CERTIFICATION.md.

## 2. Capas obligatorias de verificación

| Capa | Objeto | Pruebas mínimas | Evidencia |
|---|---|---|---|
| T0 | API, semántica, type safety | contrato/descriptor, codec input/output, ADTs, errores tipados, DSL compila/rechaza, propiedad/pureza donde proceda | tests seleccionados + XML del SHA |
| T1 | Step y dominio | éxito, error, cancelación, missing capability, seguridad de entradas, estados límite, event payload íntegro | suites reales que EJECUTAN handler/adaptador |
| T2 | Arquitectura y compatibilidad | fitness dependencia/registry/body-policy, 0 when(stepKey), no global state, corpus completo, ABI/schema | reportes de arquitectura y corpus |
| T3 | Distribución instalada | installDist/distZip, CLI contra fixture real, success/failure, fresh + replay sobre MISMA DB/controlRoot, rutas + contenidos + hashes | argv, exit, stdout redacted, XML, SHA |
| T4 | Durabilidad y fallo | kill/resume, no re-ejecutar side effects memoized, divergence fail-closed, concurrent writers, cancel/timeout, error journal | UAT reales + estado antes/después |
| T5 | Release y resistencia | checkout limpio, CI required, auditoría supply chain, artefacto reproducible, 200 MiB/1 GiB, consumidor lento, seguridad, instalación limpia | URL de CI, SBOM, SHA256, mediciones, firma/attestation si disponible |

T0/T1 no sustituyen T3/T4. Selección progresiva de tests para cada edición según AGENTS.md; T5 completo al cerrar batch/Tier/release y siempre después de cambios transversales. Pruebas omitidas son SKIPPED; infraestructura ausente es BLOCKED; no es GREEN.

## 3. Dimensiones del Step

Mantener matriz por Step con C01 descriptor, C02 input codec, C03 output codec, C04 compilación DSL positiva, C05 compilación negativa, C06 IR genérico, C07 resolución registry, C08 admisión de capacidades, C09 handler éxito, C10 fallo tipado, C11 evento tipado, C12 cancelación, C13 replay, C14 cuerpo/policy si aplica, C15 seguridad/credenciales, C16 distribución instalada, C17 compatibilidad Jenkins donde proceda, C18 escenario real, C19 plugin externo sin modificar core. Si una dimensión NO_APLICA, registrar justificación verificable según especificación vigente, NO reemplazarla por un PASS ficticio. Cada fila debe enlazar un método de test, fixture, resultado XML/CLI y SHA.

Prueba negativa esencial: la ausencia de una capacidad rechaza antes del efecto. El corpus debe medir efecto y contenido, no sólo contar eventos. Rerun del CLI no equivale a kill/resume: probar ambos por separado cuando el contrato exige durable replay. En una certificación de reporte/stash, recalcular hashes de bytes definitivos y ensayar traversal/symlinks.

## 4. Dos gates de certificación independientes

**STEP-CERT:** Step completo y ejecutable en el SHA certificado, sin bypass del registro, sin regressiones conocidas del perfil, matriz C01..C19 sustentada por pruebas REALES y receipt G0..G8. Un Step previamente certificado sigue siendo CERTIFIED_AT_OLD_SHA; si cambian sus contratos o dependencias compartidas queda pendiente de regresión en HEAD.

**PRODUCT-GATE:** todas las UAT obligatorias de PRODUCTION_READY_UAT_MATRIX.md, CI real del SHA, cobertura declarada, SAST/dependency scan, seguridad del perfil, presupuesto de rendimiento aprobado, distZip reproducible e instalación limpia. Un Step-CERT nuevo no vuelve verde el PRODUCT-GATE. **CHANNEL-GATE:** ZIP GitHub Release y SDKMAN se certifican por separado; no publicar SDKMAN_READY sin vendor publish + clean install UAT + default verify.

## 5. Formato obligatorio del recibo nuevo

~~~yaml
id: WU-RP-NNN
status: PASS|FAIL|BLOCKED|NOT_RUN|CERTIFIED_AT_SHA
base_sha: exact-full-sha
head_sha: exact-full-sha
source_tree_sha: exact-full-sha
artifact: path-or-release-url
artifact_sha256: exact-or-NOT_BUILT
scope: named-profile-and-supported-contracts
checks:
  - id: UAT-RP-XXX
    command: exact-argv
    exit_code: numeric-or-NOT_RUN
    xml: path/tests/failures/errors/skipped-or-NONE
    evidence: immutable-log-artifact-url-or-path
    result: PASS|FAIL|BLOCKED|SKIPPED|NOT_RUN
known_failures: [issue-or-evidence]
coverage: measured-value-and-report-or-UNKNOWN
security: scan-report-or-UNKNOWN
performance: baseline-budget-result-or-UNKNOWN
next_action: explicit
~~~

No incluir secretos en logs, manifests ni receipts; registrar su uso como identidades no sensibles. Nunca editar un receipt anterior para ocultar un fallo: escribir una nueva corrección/recertificación referenciando el original.

## 6. Gate y STOP

Un fallo P0, un caso de aislamiento inseguro o una prueba obligatoria ausente impide certificar. Localizar causa, reproducir, arreglar, volver a correr impacto y gates; si requiere cambiar un contrato público, cumplir las excepciones de autorización de INITIATIVE_LPR_001. No aceptar resultados de un commit base para afirmar el estado de otro commit por semejanza visual. En ausencia de permisos/infra indicar BLOCKED_EXTERNAL y mantener el gate abierto.

## 7. Gobierno de evidencia y caducidad

En toda sesión: leer .agent/SESSION_POINTER.md, comparar su last_observed_code_sha con Git/CI y enumerar surfaces que cambiaron. Declarar qué pruebas y recibos aún aplican y cuáles caducaron; emitir un nuevo recibo consolidado tras un cambio transversal. Certificación antigua: referencia histórica válida para SU artefacto, no resultado de HEAD.
