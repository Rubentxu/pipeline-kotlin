# ADR-EVO-07 — Gobernadores de Admission, Delivery y Presentation separados

- Estado: **PROPOSED**; propietario: Fabric; seam de métricas local en PK.
- Problema: colas de streams pueden saturar CPU, memoria y disco. Un filtro de logs en origen puede alterar la verdad y las actualizaciones de UI no precisan la misma prioridad que cancelación.
- Alternativas: un único buffer FIFO; drop logs al llenar; tres mecanismos con presupuestos y fairness.
- **Decisión:** `AdmissionController`, `DeliveryGovernor` y `PresentationGovernor` calculados con funciones puras y adaptadores de métricas. Classes P0 control, P1 hechos, P2 output, P3 consultas, P4 telemetry; fairness DRR por run/tenant y garantías de progreso. Histéresis; bajo presión diferir y rechazar antes de descartar datos obligatorios. Observadores nunca backpressure directo sobre stdout.
- Consecuencias: no prometer cero bloqueo ante disco finito; budgets/quotas documentados; datos BestEffort solo si son opt-in y auxiliares. Empirical tuning de lotes y thresholds, no sobreoptimización previa.
- Gate: M6 UAT-GV-001..008, AAT-31..36.
