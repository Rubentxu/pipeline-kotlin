# PipelineK Agent-First Secretless Execution — paquete de evolución

**Estado:** propuesta integrable, no implementación.  
**Fecha de preparación:** 2026-09-30.  
**Repositorio objetivo:** `Rubentxu/pipeline-kotlin`.  
**Baseline inspeccionado:** `main @ e0e32abae1b1dde06c19ab694877250368690671` (`feat(directives): S2-A - typed three-valued when gate on the S1 kernel`).  
**Autoridad que este paquete NO sustituye:** `docs/v2/05-roadmap/ROADMAP.md`, SDDK, ADRs aceptados y UAT/certificación vigentes.

## Objetivo

Evolucionar PipelineK hacia un runtime local **agent-first** capaz de ejecutar operaciones que necesitan credenciales sin obligar al agente a recibir o manipular el secreto, reutilizando al máximo la infraestructura ya certificada de Steps, capabilities, coordinator, eventos, replay y `core.sh`.

La propuesta incorpora y refina las ideas conversadas sobre:

- perfiles reutilizables para agentes y humanos;
- credenciales como capacidades de uso, no como bytes a recuperar;
- integración con Agent Secretless Vault (ASV), Linux Secret Service y el store local existente;
- adapters/projections específicos para Git/SSH, curl, Maven, Gradle, npm, Docker y otros CLIs;
- overlays efímeros de configuración en lugar de modificar `~/.npmrc`, `~/.m2/settings.xml`, `~/.gradle/gradle.properties`, etc.;
- uso simple desde `pipeline.kts`, CLI inline y, posteriormente, MCP/skills;
- ejecución inline de cualquier Step registrado, no sólo shell;
- filtros y proyecciones de salida posteriores a la redacción obligatoria;
- un modelo de plugins seguro que separa plugins de Steps de extensiones sensibles de credenciales;
- un plan explícito de compatibilidad que **protege la espina durable de `sh`**.

## Decisión principal

El primer tren de implementación **NO introduce un segundo executor de procesos**.

```text
DSL / CLI inline / MCP
        │
        ▼
Invocation facade
        │
        ▼
canonical Step IR + StepRegistry
        │
        ├── core.sh ───────────────▶ MISMO ShExecution / DurableShellExecutor
        ├── scm-git.checkout ──────▶ handler existente
        ├── junit.results ─────────▶ handler/plugin existente
        └── <plugin step> ─────────▶ handler registrado
```

La ejecución de comandos agent-first inicialmente baja a `core.sh` mediante un encoder canónico y testeado. No se crea `core.exec`, `DurableExecExecutor` ni un journal paralelo. Una futura primitiva argv-first sólo podrá existir tras extraer una autoridad de proceso común y demostrar equivalencia observable con `sh`.

## Encaje con el roadmap vigente

La propuesta encaja naturalmente en **RP-7 — Local-first ampliado**, cuya definición actual ya incluye secretos/egress, policy, almacenamiento local robusto y sandbox opcional. El paquete propone un subprograma `TRAIN-RP7-ASX-*` que debe abrirse **después de cerrar el TRAIN activo y sus gates**, sin alterar la secuencia operativa actual.

El documento [`04-roadmap/ROADMAP-PATCH.md`](04-roadmap/ROADMAP-PATCH.md) contiene un fragmento preparado para integrar como ampliación de RP-7. No debe sustituir el roadmap.

## Lectura recomendada

1. [`01-context/CURRENT-STATE-AND-GUARDRAILS.md`](01-context/CURRENT-STATE-AND-GUARDRAILS.md)
2. [`02-specifications/PRODUCT-SPECIFICATION.md`](02-specifications/PRODUCT-SPECIFICATION.md)
3. [`02-specifications/UNIFIED-INVOCATION-ARCHITECTURE.md`](02-specifications/UNIFIED-INVOCATION-ARCHITECTURE.md)
4. [`02-specifications/CREDENTIAL-CAPABILITY-MODEL.md`](02-specifications/CREDENTIAL-CAPABILITY-MODEL.md)
5. [`02-specifications/PIPELINE-KTS-DSL.md`](02-specifications/PIPELINE-KTS-DSL.md)
6. [`02-specifications/INLINE-AGENT-CLI.md`](02-specifications/INLINE-AGENT-CLI.md)
7. [`02-specifications/COMPATIBILITY-CONTRACT.md`](02-specifications/COMPATIBILITY-CONTRACT.md)
8. [`04-roadmap/ROADMAP-INTEGRATION.md`](04-roadmap/ROADMAP-INTEGRATION.md)
9. [`05-uat/UAT-MASTER-PLAN.md`](05-uat/UAT-MASTER-PLAN.md)
10. [`05-uat/GATE-TEETH.md`](05-uat/GATE-TEETH.md)

## No objetivos del primer tren

- Reescribir `DurableShellExecutor`.
- Introducir un nuevo proceso durable paralelo.
- Cambiar payload, fingerprint, replay o identidad durable de `core.sh` existente.
- Convertir PipelineK en un vault alternativo a ASV.
- Permitir a un plugin arbitrario in-process leer secretos.
- Prometer aislamiento fuerte donde sólo existe redacción o exposición de proceso.
- eBPF para parchear secretos directamente en memoria de procesos.
- Cambiar silenciosamente el significado del `withCredentials(...)` compatible con Jenkins.

## Principio rector

> PipelineK orquesta **operaciones y capacidades**. Los secretos son un detalle de satisfacción de una capacidad, no datos que el agente deba recibir.

