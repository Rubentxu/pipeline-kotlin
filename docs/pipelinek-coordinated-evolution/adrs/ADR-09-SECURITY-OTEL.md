# ADR-EVO-09 — Seguridad antes de exportación, OTel como integración auxiliar

- Estado: **PROPOSED**; propietarios: PK+Fabric.
- Problema: exporter/indexador y Jenkins son zonas de confianza distintas. Redaction en renderer llega demasiado tarde; trazas no tienen semántica de journal ni durability ACK de facts.
- Alternativas: enviar raw output a ELK para redactar después; Collector para todo; redactar en PK antes de commit, segregar typed values y exportar telemetría opcional.
- **Decisión:** la tercera. Redactar bytes antes de Output Plane, ACL en vistas, cifrado local/remoto, mTLS/autenticación Worker↔Fabric, scopes por acción, control terminal ANSI/HTML en presentación y cursor firmado. OTel spans/métricas contextualizados, sampling permitido solo de señales auxiliares. Crash exporter no afecta pipeline outcome.
- Consecuencias: más tests de secretos cruzando buffers, altas cardinalidades fuera de índices de métricas, secreto en typed return jamás en consola por comodidad. Agent-secretless se integra mediante SPI opcional, no hard dependency.
- Gate: M7 UAT-SEC-001..008, AAT-37..44.
