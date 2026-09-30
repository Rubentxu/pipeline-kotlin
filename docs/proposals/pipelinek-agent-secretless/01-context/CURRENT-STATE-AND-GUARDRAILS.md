# Estado actual y guardrails de integración

## 1. Estado observado

El paquete se preparó inspeccionando `pipeline-kotlin` en `main @ e0e32abae1b1dde06c19ab694877250368690671` el 2026-09-30.

La autoridad documental vigente continúa siendo:

- `docs/v2/05-roadmap/ROADMAP.md` como secuencia única;
- `docs/v2/00-governance/ACTIVE_DOCUMENTS.md` como índice activo;
- `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md` y `PRODUCTION_READY_UAT_MATRIX.md` como prueba vinculante;
- SDDK + Git + ADRs + evidencia del SHA exacto para estado operativo.

HEAD observado está en el TRAIN S2 de evolución semántica/directivas. Este paquete no presupone que ese TRAIN esté cerrado ni reclama prioridad automática sobre él.

## 2. Activos que se deben proteger

PipelineK ya ha invertido de forma sustancial en:

- `core.sh` como Step canónico;
- `ShellOperations` como capability tipada;
- `ShExecution` y `DurableShellExecutor`;
- journal durable, replay, reconciliation y operation identity;
- watchdog/cancelación/process-tree handling;
- workspace y overlays de contexto;
- redacción de secretos;
- streaming de transcript;
- `StepRegistry`, `StepDefinition`, codecs y capabilities fail-closed;
- `RegistryStepSpec`/`RegistryBlockSpec` como seam open-world;
- `StepDefinitionContributor` para plugins externos;
- un modelo de testing con Gate Teeth y evidencia instalada.

El evolutivo secretless debe **componer** estas piezas, no crear una segunda arquitectura.

## 3. Restricciones innegociables

### G-01 — Single execution spine

No se crea un segundo executor durable para comandos.

Prohibido en el primer tren:

```text
DurableExecExecutor
ExecJournal
ExecReplayPolicy
ExecCoordinator
```

### G-02 — `core.sh` legacy equivalence

Para una pipeline existente que no usa las nuevas capacidades, deben permanecer equivalentes:

- payload canónico de `core.sh`;
- fingerprint;
- durable operation identity;
- secuencia y significado de eventos;
- outcome/exit semantics;
- replay/restart;
- redaction semantics;
- workspace/cwd;
- timeout/cancellation.

### G-03 — Legacy `withCredentials` frozen

El DSL existente con `CredentialsBinding` conserva su significado actual. No se convierte silenciosamente en un planner que elija proxies/config overlays.

### G-04 — New semantics use new IR

Las nuevas formas basadas en profiles/capabilities deben bajar a un nuevo contrato versionado, preferentemente un `RegistryBlockSpec`, en lugar de cambiar el payload de `StepSpec.WithCredentialsBlock` existente.

### G-05 — No secret retrieval API for agent-facing surfaces

No se añade `getSecret`, `exportSecret`, `resolveRawSecret` ni equivalente a CLI/MCP/DSL agéntico.

### G-06 — Redaction is not confinement

La redacción exacta de stdout/stderr es defensa en profundidad, no una barrera suficiente frente a una herramienta maliciosa que posee el secreto. La postura de seguridad debe declarar exposición real.

### G-07 — No repository litter by default

Profiles, estado efímero y config de agent execution viven en directorios del usuario o control roots existentes, no en el repo de trabajo salvo opt-in explícito.

## 4. Encaje con Agent Secretless Vault

ASV mantiene una invariante más fuerte que un vault tradicional: la API orientada al agente no entrega el material secreto. PipelineK debe integrarse con esa invariante, no perforarla implementando ASV como un `CredentialProvider.resolve(): SecretHandle` genérico.

La integración deseada es capability/session-oriented:

```text
PipelineK Credential Planner
          │
          ▼
ASV adapter
          │
          ├─ signer/session/proxy/connector
          └─ isolated exec fallback
```

No:

```text
ASV → get bytes → SecretHandle → env
```

salvo que ASV exponga de forma explícita una modalidad degradada y ésta se etiquete con su postura real.

## 5. Por qué RP-7

El roadmap actual define RP-7 como local-first ampliado con:

- secretos/egress;
- policy;
- almacenamiento local robusto;
- sandbox opcional OS/container;
- extensibilidad de plugins con identidad/provenance;
- spike Cedar/policy antes de enforcement.

El subprograma propuesto encaja directamente en ese ámbito. No requiere adelantar RP-8 control plane ni RP-9 Jenkins/Kubernetes.

