# Performance Benchmark Plan

## Objetivos

Medir el beneficio real de mover runtime a workers y evitar optimizaciones anecdóticas.

## Benchmarks

### Compiler
- cold compile p50/p95;
- warm cache compile;
- process vs in-process/host;
- scripts 50/200/1000 LOC;
- 10/50/200 plugins en classpath.

### Worker startup
- local;
- Kubernetes cached image;
- Kubernetes cold image pull;
- warm pool.

### Eventing
- events/sec;
- ACK latency;
- reconnect replay 1k/10k/100k events;
- projector throughput;
- snapshot rebuild.

### Controller
Comparar Groovy Pipeline equivalente vs V2:
- CPU;
- heap;
- threads;
- serialized state size;
- run concurrency.

### Graph
- projection throughput;
- causal chain query;
- artifact provenance query;
- 10k/1M/10M relation datasets según etapa.

## Budgets iniciales

No fijar números GA sin entorno reproducible. M0 crea harness; M3/M4 establecen baseline; M9 convierte datos reales en SLO/performance gates.

## Regresión

CI nightly conserva trends. Release candidate falla si supera budget acordado en métricas críticas sin waiver documentado.
