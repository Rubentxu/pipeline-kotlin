# AAT PipelineK + puntos de contacto

**Conserva IDs originales para gate cruzado.** Un PASS exige ejecución real, SHA+artifact y evidencia; `NOT_MEASURED` no desbloquea.

| ID | Escenario | Oráculo | Entorno / contexto |
|---|---|---|---|
| AAT-01 | M1 | writer SIGKILL en prepare/commit | nada no comprometido visible, recovery idempotente |
| AAT-02 | M1 | 2 JVM, 1 writer+2 readers | reader no recupera/destruye writer vivo |
| AAT-03 | M1 | millones de frames y tail pequeño | coste sin escaneo completo; memoria bounded |
| AAT-04 | M1 | stdout/stderr simultáneos, UTF-8 y secretos | sin deadlock/redaction correcta/read cursor estable |
| AAT-16 | M3 | network partition y store local activo | datos retenidos dentro SLA, saturación tipada |
| AAT-27 | M5 | worker muerto después de prune local | lectura completa desde Archive backend |
| AAT-37 | M7 | secrets divididos y redacción simultánea multistream | ninguna fuga en store, spool, archive, indexador |
| AAT-38 | M7 | 2 versiones PK/Fabric con handshake | compat N/N-1 o refusal explícito |
| AAT-39 | M7 | exporter telemetry bloquea durante minutos | ejecución y salida no dependen del exporter |
| AAT-42 | M7 | 24h soak y múltiples restarts | leak slope aceptable, offsets consistentes |
| AAT-43 | M7 | alta tasa de fsync/IO latencia | clase presión eficaz, ningún uso unbounded |
| AAT-44 | M7 | recovery + release artifact real | reproducibilidad, instalación y lectura histórico |
