# SPEC-08 — Seguridad, observabilidad OTel y resiliencia operativa

**Owner:** PipelineK y Fabric en seams propios · **Prioridad:** transversal, certificación M7. **ADRs:** ADR-02, ADR-09. **Referencias:** PK ADR-0085/0086, Fabric ADR-0012/0024, Jenkins seams, propuestas agent-secretless como evolución no obligatoria.

## Modelo de amenaza

Actores: usuario CLI no autorizado, Jenkins plugin comprometido, worker zombie con token viejo, procesos `sh` maliciosos, salida ANSI/HTML, otros tenants, backend S3/Elastic externo, operador con acceso parcial, plugin paso-por-paso, red particionada. Atacables: comandos/cancel, lectura de logs, objetos compartidos, keys en manifests, links temporales, indexación, valores tipados y trazas.

### Reglas

- Autenticación mTLS o identidad mutua equivalente Worker↔Fabric; rotación, expiración, revocación y scope explícitos. Token de fencing separado de autorización principal.
- ACL por tenant/proyecto/run/acción (`start`, `cancel`, `inspect`, `read-output`, `search`, `admin`); ninguna operación read-side concede cancel.
- Secret redaction streaming **antes** del commit de bytes observables, incluyendo secretos cruzando límites de chunk. No redaction-only al presentar.
- TypedStepValue, `returnStdout`, credenciales y journal sensible no viajan por el canal público de output; exportador OTel no serializa secretos ni comandos con valores sensibles.
- Encryption at rest, TLS, credenciales scoped para backend, objectKey no adivinable/público y autorización de rangos. Client-facing URL firmada solo si ADR futuro justifica subida/descarga directa.
- Sanitizar salida de terminal (control sequences, OSC/CSI, bidi, multiline); preservar bytes sanitizados canónicos de PipelineK y aplicar **seguridad de presentación** en UI.
- No permitir retry de mutación con comando diferente bajo la misma idempotency key ni accept de writer fenced.
- Cada respuesta de error tiene nivel público: usuario ve reason segura, operadores obtienen correlationId, nunca stack trace/secret en stdout.
- Backend externo es zona de confianza distinta; redactar/exportar solo datos con política de clasificación.

## Telemetría OpenTelemetry

Instrumentar `run`, `stage`, `step`, `operation`, `recovery`, `reconciliation`, `replication`, `archive.write/read`, `query`, `watch` y `governor` con trace/span correlacionable. `runId`/`operationId` pueden ser atributos de alta cardinalidad en trazas pero no índices de métricas indiscriminados. Métricas: lag, ack age, spool bytes, disk pressure, read duration, scan bytes, retries, duplicate replay, integrity errors, cancel latency, observer lag.

Collector/Elastic/Loki son adaptadores opcionales. Timeout/bad credentials/exporter down no reinterpreta resultado ni incrementa indefinitely heap. Sampling permitido solo para spans y diagnóstico auxiliar; sin sampling EventStore/Output obligado. No duplicar grandes logs en spans.

## Política de red y entorno

No egress arbitrario desde steps por crear seam de archivo; el uploader Fabric tiene permisos acotados. El runtime respeta perfiles sandbox actuales y capabilities declaradas, evita dependencia de agent-secretless para ejecutar local; integración agent-secretless optional en futuro con SPI de credenciales sin volcar secreto en logs.

## Cierre M7

Security tests de ACL, cross-tenant, redaction split/overlap, token stale, URL de objeto vencida, manipulación de cursor, JSON/ANSI injection, query DoS, backend indisponible, exporter fuera de servicio y retorno a carga normal. Supply-chain SBOM/lockfile, dependency/license audit y scan de secrets conforme a cada repo.
