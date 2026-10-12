# Fitness functions, presupuestos de rendimiento y anti-regresión

## Reglas de establecimiento

No se fabrican objetivos con datos no medidos. M0 recopila baseline reproducible y capacidad del hardware de prueba; M1 fija límites intrínsecos PK; M3 M4 M5 M6 ratifican presupuestos por escenario. Los siguientes valores son **objetivos iniciales de diseño, no resultados medidos ni garantías SLA publicables**, que deben ratificarse con un spike y registrar motivo de revisión.

| Métrica | Propuesta de objetivo inicial | Método / advertencia |
|---|---|---|
| `pk_first_partial_byte_p95` | < 500 ms en loopback tras byte elegible de redaction | Medir instante justo después de lookahead requerido, no al `write` del secreto |
| `fabric_live_update_p95` | < 2 s sin presión | Medir evento commit→lectura remota, separar polling fallback |
| `p0_cancel_delivery_p95` | < 2 s bajo saturación de output | Control recibido, no tiempo a terminar proceso; SLA revalidado con hardware |
| `source_gap_count` | 0 si fuente conserva rango | En caso de retención expirado, explícito Gap en lugar de 0 falso |
| `false_durable_acks` | 0 | ACK verificado con read-after, manifest y restart según backend |
| `out_of_order_advances` | 0 | No saltar un rango no confirmado |
| `secret_exposure_count` | 0 | Corpus canónico de secretos partido |
| `double_side_effect_count` | 0 | Reconciliación de deploy con marker único |
| `controller_cpu_silent` | Baseline + presupuesto explicado, no polling fijo por cliente | CPU requests/s y heap con 20 builds quietas |
| `reader_memory_peak` | O(configuredPageBytes), no O(logBytes) | Escalar logs 10x, misma page size, verificar meseta |
| `worker_memory_peak` | ≤ quota + reserva JVM explícita | Incluir heap/direct buffers/threads, no solo heap |
| `archive_local_bytes` | ≤ quota, salvo metadata/overhead declarado | Test con backend remoto caído y quota límite |
| `query_scanned_bytes_ratio` | Filtrado estructural reduce IO frente a full scan | Comparar mismo resultado de consulta stage+stderr |
| `p1_progress_min` | >0 durante flood P2 si backend admite commits | No starvation, límites p95 ratificados |
| `otel_exporter_failure_propagation` | 0 cambios en execution outcome | Crash exporter/export retries saturados |

## Corpus de carga

1. **Quiet:** 20–100 runs activos que no escriben por 10–20 min, Jenkins sigue mostrando RUNNING y CPU baja.
2. **Partial:** `printf abc; sleep`, luego unicode UTF-8 y stdout/stderr simultáneos.
3. **Flood:** 1–4 runs stdout continuo, 1 run pequeño stderr+failure, 1 cancel, varios watchers con throughput de lectura artificialmente limitado.
4. **History:** 1 GiB+ de salida archivada con lecturas aleatorias de 16 KiB y últimos 100 registros. Generar mediante streaming/fixture, no materializar 1 GiB en `String`.
5. **Partition:** 1–15 minutos con tasa ajustada al buffer disponible; medir tiempo para llenar, comportamiento al límite y drenaje.
6. **Jenkins:** 1, 10, 100 consumidores, y dos procesos simultáneos sobre el mismo run; controlar heap/controller load.

## Perf discipline

- Guardar CPU, RSS, heap, GC, threads, fd, bytes/s, syscalls/fsync, latencia p50/p95/p99 y tamaño físico spool/archivo.
- Comparaciones sobre mismo commit de base, misma JVM, tamaños y hardware; medir warm-up vs steady state; no convertir números accidentales de una máquina en tests rígidos estadísticos.
- Ratchets arquitectónicos: dirección de dependencias hexagonal, no dependencia de Jenkins/Fabric desde PK, ni llamadas remotas desde pumps, no más de un reducer autoritativo, ADTs exhaustivos sin `else` indiferenciado, colas bounded, no grandes stdout `StringBuilder`.
- Si hay regresión, perf diagnosis antes de aumentar parámetros; bloquear release por invariantes de corrección o presupuesto **ratificado**, no por benchmark ambiguo.

## Herramientas recomendadas (opcionales según stack)

Gradle TestKit y tests de integración existentes; JVM Flight Recorder/async-profiler, JMH para microbenchmark si necesario, `tc netem`/Toxiproxy para red, contenedores de pruebas para object store. Nunca exigir SonarQube, Elastic ni un servicio externo solo para lanzar suites locales.
