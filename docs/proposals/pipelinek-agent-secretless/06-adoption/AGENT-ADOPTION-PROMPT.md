# Prompt para que un agente adopte este paquete

Integra el paquete `pipelinek-agent-secretless-evolution` como propuesta evolutiva dentro de `pipeline-kotlin` siguiendo la gobernanza vigente.

Reglas obligatorias:

1. Recupera primero el estado real con SDDK, Git y `docs/v2/05-roadmap/ROADMAP.md`; no uses este paquete como cola paralela.
2. Reasigna los IDs `ADR-PKAS-*` y `ASX-*` a IDs libres según el estado actual. No colisiones ni reescritura de ADRs/receipts históricos.
3. Integra la planificación como subprograma de RP-7, manteniendo el roadmap único como autoridad.
4. No implementes todavía si el TRAIN actualmente activo no está cerrado o el roadmap no autoriza abrir RP-7/este subprograma.
5. Antes de cualquier código crea el compatibility fortress: characterization de `core.sh`, fingerprint/replay/eventos y legacy `withCredentials`.
6. Está prohibido crear `DurableExecExecutor`, un journal de procesos paralelo o modificar semántica durable de `core.sh` para facilitar este evolutivo.
7. `withCredentials(CredentialsBinding...)` queda congelado; las nuevas semantics profile/capability usan IR/versionado separado.
8. Todo cambio se hace con testing quirúrgico según `AGENTS.md`; L5 sólo en boundary de integración/release.
9. Cada gate crítico debe tener Gate Teeth/falsifiability.
10. Todo secretless claim declara posture real y falla cerrado si no alcanza el mínimo requerido.
11. Agent Secretless Vault se integra por sesión/capability; no introduzcas una API genérica de exportación de secretos.
12. No dejes estado/config de PipelineK en el repo de trabajo por defecto.
13. Commits atómicos y Conventional Commits; un WU sólo se declara DONE con criterios/UAT/evidencia verificadas.

Primera tarea: compara este paquete con la arquitectura y roadmap del HEAD actual, produce un disposition `ADOPT / ADAPT / DEFER / REJECT` por documento/ADR, y prepara el patch documental mínimo sin tocar código productivo.

