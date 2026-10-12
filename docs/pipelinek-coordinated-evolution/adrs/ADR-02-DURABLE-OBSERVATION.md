# ADR-EVO-02 — Cuatro planos lógicos, autoridades y wakeups no durables

- Estado: **PROPOSED**, compatible con PipelineK ADR-0085/0086/0088; fecha 2026-10-10.
- Problema: consola, hechos, valores tipados, journal y traces tienen garantías distintas. Un único log/text stream con renderizadores en hot path impondría backpressure remoto y falsas asociaciones causales.
- Alternativas: todo en evento; todo en transcript; output+event+telemetry+control por carriles lógicos y stores propios.
- **Decisión:** Output = bytes redactados + stream offsets + frames; Events = secuencia de hechos; Telemetry = opcional/sampled; Control = comandos/leases/fencing con prioridad. Journal interno y valores tipados no se distribuyen como consola. Wakeup solo hint tras commit, cursor durable autoridad; Flow no store. No inventar orden total entre planes.
- Consecuencias: todos los consumidores comparten read-side; errores son ADTs; UTF-8 incremental; sin sampling de events ni `returnStdout` exportado por defecto. Consume algo de esfuerzo correlacionar eventos y frames por IDs estables, pero evita acoplamiento y duplicación.
- Gate: UAT-PK-001..008, AAT-01..04. ADR adoptados existentes no se sustituyen; este ADR define seam cross-repo.
