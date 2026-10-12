# UAT PipelineK + interoperabilidad PK↔Fabric

**Conserva IDs originales para gate cruzado.** Un PASS exige ejecución real, SHA+artifact y evidencia; `NOT_MEASURED` no desbloquea.

| ID | Escenario | Oráculo | Entorno / contexto |
|---|---|---|---|
| UAT-PK-001 | Proceso emite `abc` sin LF, duerme 30 s | CLI y reader PK ven bytes mientras PID vivo | PK subprocess |
| UAT-PK-002 | stdout/stderr concurrentes en ráfagas | Cada canal exacto, frame ordinal monotónico, no deadlock | PK subprocess |
| UAT-PK-003 | run emite StageStarted y continúa | EventReader ve hecho previo a resultado terminal | PK real |
| UAT-PK-004 | observador se mata en medio | Proceso y escritura durable siguen; nuevo lector retoma cursor | 2 JVM |
| UAT-PK-005 | writer SIGKILL entre reserve/write/commit | lector no destruye writer ni lee no comprometido, recuperación idempotente | 2 JVM |
| UAT-PK-006 | secreto partido en 3 chunks | ninguna ventana del Output Plane ni CLI contiene secreto | PK redactor |
| UAT-PK-007 | multibyte UTF-8 partido entre frames | reconstrucción correcta sin reemplazos espurios | PK reader |
| UAT-PK-008 | `sh(returnStdout=true)` con valor sensible | valor semántico correcto; no aparece en output público por esa vía | PK real |
| UAT-RP-003 | EventStore incrementa durante sh | Fabric ve hechos antes del fin | PK+Fabric |
| UAT-RP-004 | ConsoleSource recibe salida parcial | Fabric expone byte range mientras running | PK+Fabric |
| UAT-AR-006 | ACK remoto válido + retención elegible | único segmento se libera tras falta de pins | real store |
| UAT-AR-007 | run terminal sin ACK archive | retención NO borra copia necesaria | real store |
| UAT-Q-001 | `--stage Deploy --channel stderr` | bytes de esa operación y canal, sin vacío falso | Fabric+PK |
| UAT-SEC-001 | secret multi-chunk | ninguna copia observable lo contiene | PK+archive |
| UAT-SEC-002 | typed value secreto | no pasa a collector/log/search | PK+Fabric |
| UAT-SEC-006 | exporter OTel caída | outcomes/logs intactos | PK+Fabric |
| UAT-SEC-008 | recuperación tras restart/upgrade | ABI/schema con versiones negociadas, sin outcome cambiado | releases |
