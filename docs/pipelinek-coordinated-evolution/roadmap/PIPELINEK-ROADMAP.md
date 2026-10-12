# PipelineK — roadmap propio subordinado al par M0–M7

**Repo propietario:** https://github.com/Rubentxu/pipeline-kotlin · baseline main `543e1cc5e61f6c92fe1c7ffbd8b513bbe90395b4` (10-10-2026). **Pareja:** https://github.com/Rubentxu/pipelinek-fabric, HEAD observado `e6e4fd3e191dd483994af89418c93ecd5ffdb3e0`. Revalidar refs y código en cada preflight.

**Autoridad única:** `docs/v2/05-roadmap/ROADMAP.md` en PipelineK, vinculando este evolutivo. Este documento es especificación de la iniciativa, no estado general alternativo. Consultar `coordination/MILESTONE_MATRIX.md` para secuencia cruzada.

## Principio de independencia

PipelineK no cambia ejecución para satisfacer requisitos de un navegador o de una red. Publica puertos pequeños y certifica sus garantías con un consumidor externo. **No incorporar Fabric/Jenkins/gRPC/S3 al dominio.** Tests focales durante desarrollo, full integration/release al corte; SIGKILL no se confunde con pérdida de alimentación. Todos los cambios en ADR adoptados requieren resolución explícita, no suplantación.

## Hitos / gates de PipelineK

| Par | Trabajo PipelineK | Gate PK antes de handoff | Dependencia de otro repo para cerrar PAR |
|---|---|---|---|
| M0 | Medir ABI, capacidad actual, tags, reader, retention; aceptar contrato CRIC-1 | Baseline / consumer smoke publicado | Fabric corrige facts+spool y publica release |
| **M1** | **Gran bloque PK**: completar OBS-PC seguro live, Event+Output reader desde otra JVM, wakeups commit, canales, UTF-8 incremental, context mapping y ABI/capabilities | UAT-PK-001..008, AAT-01..04, verificación del SDK/distribution publicada; release remoto PK tras consumer-candidate green | Fabric usa PK publicado y demuestra observación real incremental, publica Fabric; pair gate |
| M2 | Solo seam inspect/recover/cancel si los puertos existentes lo requieren; no escribir reconciler distribuido | Consumer compila contra PK publicado, recover no duplica efectos | Worker reconciler probado por Fabric + pair gate |
| M3 | Si hace falta, exponer reader por rango + frame metadata + pin retenible de forma genérica; nunca segunda autoridad de replicación | no hidden ABI/layout, lectura por cursor durable | Fabric permite ACK y repl sin huecos |
| M4 | PK sin adaptación Jenkins; asegurar soporte estable de reading | regresión a última PK publicada | Jenkins real + CLI mismo RunId en Fabric |
| M5 | Pin/release genérico solo SI la retención actual no permite conservar bytes hasta ACK Fabric, manteniendo local-first | UAT-AR-006/007, AAT-27; release PK si cambia ABI | Fabric local/S3 archive + validación en pareja |
| M6 | Enriquecimiento contextual stage/step/operation y presión local solo si imprescindible | UAT-Q-001 + bytes correctos, presión no descarta semántica | QueryPlanner/governor Fabric certificado |
| M7 | OTel instrumentación opcional, hardening, seguridad, reproducibilidad de releases; no duplicar EventStore | UAT-SEC-001/002/006/008, AAT-37/38/39/42/43/44; release PK si cambia | Fabric certifica final de par sobre artifacts remotos |
| OPC-K | NINGÚN CRI/containerd shim necesario por defecto | build local sin Kubernetes | Fabric provee CapacityProvider opt-in |

## Ruta recomendada

1. Antes del bloque M1, inspeccionar `docs/v2/05-roadmap/OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` en HEAD; medir uno a uno OBS-1..OBS-7. No ejecutar un evolutivo duplicado donde ya existe código aceptado.
2. Implementar puertos `OutputReadPort`, `OutputTailPort`, evento incremental y adaptadores con formas ya presentes; definir nuevas interfaces solo para capabilities ausentes. `OperationJournal` sigue privado.
3. Una vez listo el proveedor, generar `PK_CONTRACT_HANDOFF.md` con SHA, ABI real (`javap`/consumer), capability manifest, suites, UAT, artifact checksum, limitaciones y versión.
4. **Bloqueo:** no declarar M1 DONE ni empezar la integración M2 hasta que Fabric haya producido `FAB_CONSUMER_VERDICT.md` y `PAIR_RECEIPT.json` firmado/validado externamente con `PAIR_CERTIFIED`.
5. Cambios PK en M5/M6/M7 repiten exactamente handshake, candidate → published release → consumer real → pair certification. No publicar Fabric basado solo en SDK instalado localmente.

## Salida de un hito PK

Commit Conventional, árbol limpio, tests focales y full suite en release, contrato/ADR/ABI revisados, tag remoto publicado, SDK descargable con sha256, UAT de PK real, recibo propio y enlace al par. **El avance de PipelineK a funciones locales independientes queda permitido; lo que se bloquea es promocionar el siguiente milestone de esta iniciativa sin cierre del par.**
