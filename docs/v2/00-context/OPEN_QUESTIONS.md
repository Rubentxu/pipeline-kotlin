# Open Questions

Preguntas que deben resolverse con spikes/evidence, no por preferencia.

1. ¿Qué subset de Kotlin scripting APIs necesita realmente el adapter 2.4?
2. ¿Cómo construir stable logical operation IDs robustos ante refactors benignos?
3. ¿Qué cambios de pipeline source permiten recovery y cuáles obligan a fork?
4. ¿SQLite WAL es suficiente como journal worker inicial bajo parallel intenso?
5. ¿Qué transport WebSocket API encaja mejor dentro del plugin Jenkins sin cargar controller?
6. ¿Cuándo merece separar Worker Gateway en proceso obligatorio vs modo embedded para instalaciones pequeñas?
7. ¿Qué graph store ofrece mejor coste/operación para proyección global real?
8. ¿Qué mappings de Jenkins Kubernetes PodTemplate pueden preservarse sin semántica sorprendente?
9. ¿Qué first-party plugins cubren el 80% del corpus real objetivo?
10. ¿Cómo modelar `stash/unstash` para locality y stores remotos sin copiar semántica ineficiente?
11. ¿Qué forma final de plugin signing/OCI se adopta tras el spike?
12. ¿Hasta qué punto `script {}` debe limitar APIs no-durables del lenguaje/runtime?
13. ¿Cómo detectar accidental nondeterminism (`Random`, clock, filesystem reads) y ofrecer wrappers durables?
14. ¿Qué nivel de sandbox se soporta oficialmente en local worker vs Kubernetes?
15. ¿Qué features Jenkins controller-control se implementan primero: input, build trigger, locks, milestones?
