# SLO / Reliability Targets

> Los valores numéricos finales se fijan en M9 tras obtener baseline reproducible. Este documento define las dimensiones obligatorias.

## Availability/Correctness SLO

- accepted event durability;
- run state reconstruction success;
- credential redaction correctness;
- fencing rejection correctness;
- artifact digest integrity;
- protocol compatibility during rolling upgrade.

## Latency SLO

- queue→worker requested;
- worker requested→ready;
- compile cold/warm;
- event worker→accepted;
- accepted→Jenkins/graph projected;
- cancel request→worker acknowledged.

## Recovery SLO

- reconnect success;
- Pod loss classification;
- run recovery time;
- journal replay throughput.

## Error budget

No se permite usar error budget para invariants de integridad como:
- repetir deployment irreversible por replay;
- aceptar fencing token antiguo;
- filtrar secret;
- asociar artifact a provenance incorrecta.

Esos son correctness requirements, no availability trade-offs.
