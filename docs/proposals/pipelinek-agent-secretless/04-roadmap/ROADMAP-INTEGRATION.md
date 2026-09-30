# Integración con el roadmap vigente

## 1. Ubicación

Este programa se integra en **RP-7 — Local-first ampliado**, no como roadmap paralelo.

Motivos:

- RP-7 ya incluye secretos/egress y policy;
- no depende de RP-8 remote control plane;
- aprovecha Step ecosystem de RP-6 pero no exige nuevos Steps core;
- mejora el uso local/agent-first sin cambiar la autoridad durable.

## 2. Precondiciones

Antes de abrir implementación:

1. cerrar honestamente el TRAIN activo del momento;
2. tener `main` en estado conocido respecto a L5/candidate gates;
3. registrar base SHA en SDDK;
4. caracterizar `core.sh` y `withCredentials` sobre ese SHA;
5. reservar IDs reales de ADR/WU al integrar: los `ADR-PKAS-*` de este pack son nombres provisionales y **no deben colisionar** con ADRs existentes.

Diseño/spikes docs-only pueden integrarse antes si el roadmap lo permite, pero no adelantan implementación.

## 3. Trains propuestos

### TRAIN-RP7-ASX-0 — Compatibility fortress

**Objetivo:** demostrar qué no se puede romper.

- characterization de `core.sh` payload/fingerprint/events/replay;
- characterization legacy `withCredentials`;
- fixture installed binary;
- Gate Teeth que detectan desviación;
- documentar current secret materialization choke points.

**Exit:** baseline inmutable y canarios rojos cuando se muta cada ley.

### TRAIN-RP7-ASX-1 — Credential capability kernel

- `CredentialRef`, `CredentialProfileRef`;
- `CredentialUseRequest`;
- `IntegrationPosture`;
- `CredentialAccessPlan`;
- planner puro;
- profile schema/parser sin secretos;
- admission fail-closed.

**No ejecución nueva todavía.**

### TRAIN-RP7-ASX-2 — Credential scope DSL

- nuevo `core.credentials.scope` RegistryBlock v1;
- overload `withCredentials(profile(...))`;
- `withProfile` mínimo;
- lease por Step;
- legacy bindings untouched;
- events de scope/posture sanitizados.

### TRAIN-RP7-ASX-3 — Providers fuertes/locales

Orden sugerido:

1. local provider adapter sobre infraestructura existente;
2. Linux Secret Service;
3. Agent Secretless Vault session adapter;
4. environment compatibility provider sólo degraded.

**Exit:** planner elige provider/posture y falla si minimum posture no se satisface.

### TRAIN-RP7-ASX-4 — Tool adapters + projections

Primera vertical útil:

- Git/SSH;
- curl/HTTP;
- Maven;
- Gradle;
- npm;
- Docker.

Después: pip/Cargo/Terraform según dogfooding.

Toda config sensible es overlay efímero y typed.

### TRAIN-RP7-ASX-5 — Inline agent invocation

- `pipelinek command` shell-backed;
- `pipelinek sh` profile-aware;
- `pipelinek step` genérico;
- `steps list/describe`;
- `profiles list/describe`;
- `--output agent/summary/events`;
- no repo litter.

### TRAIN-RP7-ASX-6 — Ephemeral flow + agent surfaces

- transient flow assembler;
- state en XDG user dirs;
- MCP/skill surface sobre application ports;
- tool discovery/introspection;
- no implementación duplicada en MCP.

### TRAIN-RP7-ASX-7 — Certification/release admission

- full UAT matrix;
- adversarial credential leak tests;
- installed binary;
- restart/replay;
- timeout/cancel cleanup;
- provider absent/degraded cases;
- multi-tool dogfooding en al menos dos repos;
- release notes con posture/limitations.

## 4. Programa separado posterior — PXR

`PXR` = Process eXecution Runtime refactor.

No forma parte de ASX V1.

Sólo se abre si existe valor probado en eliminar la shell intermedia para argv y si se puede extraer autoridad común sin cambiar `sh`.

Slices:

1. characterization adicional del executor;
2. mapear shell-specific vs process-generic;
3. extraer lifecycle common sin nueva API pública;
4. equivalence gate;
5. decidir GO/NO_GO a `ArgvInvocation`;
6. sólo entonces valorar `core.exec`.

## 5. Dependencias con RP-6/RP-8

- RP-6 Steps/plugins pueden beneficiarse de credential requirements, pero ASX no debe bloquear todos los plugins no credenciales.
- RP-8 puede reutilizar profiles/credential capabilities en remote workers, pero ASX V1 no depende del protocolo remoto.

