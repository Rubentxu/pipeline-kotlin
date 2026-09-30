# UAT Master Plan — Agent-First Secretless Execution

## 1. Filosofía

La certificación debe probar simultáneamente:

- que lo nuevo funciona;
- que lo antiguo no cambia;
- que los gates pueden detectar una regresión real;
- que una posture fuerte no se obtiene por etiquetado;
- que la distribución instalada se comporta igual que los tests in-process.

## 2. Familias

### UAT-ASX-COMP — Compatibility

- legacy `sh` canonical payload/fingerprint;
- replay same db/control-root;
- timeout/cancel;
- legacy `withCredentials` env binding;
- installed binary legacy corpus.

### UAT-ASX-PLAN — Planner/policy

- provider preference;
- audience mismatch;
- minimum posture;
- no provider;
- conflicting profiles;
- deny overrides allow;
- deterministic composition.

### UAT-ASX-SCOPE — DSL/scope

- `withCredentials(profile)` body;
- nested scopes;
- multiple profiles;
- per-Step lease;
- revoke on Step completion;
- revoke/cleanup on timeout/cancel;
- legacy overload unaffected.

### UAT-ASX-PROVIDER

- local provider;
- Secret Service available/locked/unavailable;
- ASV session path;
- ASV absent;
- degraded env provider requires explicit posture acceptance.

### UAT-ASX-PROJECTION

- permissions 0600/0700;
- path confined to run dir;
- Maven settings;
- Gradle config + cache separation;
- npm userconfig;
- curl netrc;
- Git helper/SSH socket;
- Docker config;
- deletion in all terminal paths.

### UAT-ASX-INLINE

- `command` args with spaces/quotes/newlines where supported;
- command uses `core.sh` canonical runtime;
- `step` routes through registry;
- missing capability fails before handler;
- introspection schema matches installed plugin;
- no repo files created.

### UAT-ASX-OUTPUT

- secret appears in raw child output canary;
- canonical event store receives redacted value;
- filter cannot recover raw value;
- agent renderer compact/deterministic;
- filter selection does not alter RunOutcome.

### UAT-ASX-FLOW

- transient flow same Step semantics;
- replay/resume according to selected durable mode;
- state outside repo;
- failure stops/continues according to canonical semantics, not a new engine.

## 3. Installed binary requirement

Cada provider/projection/CLI vertical debe tener al menos un test con distribución instalada. Unit tests de adapters no bastan.

## 4. Real-tool UAT

Mínimo para cierre:

- Git/SSH o Git HTTPS;
- curl/HTTP;
- Maven o Gradle publish hacia fixture server;
- npm publish/login fixture;
- Docker sólo si el entorno de certificación dispone de daemon controlado; si no, queda separado y no se declara certified.

## 5. Leak surfaces

Escanear:

- stdout/stderr;
- event DB;
- journal;
- control dirs retenidos;
- process argv observable;
- child env when posture claims no-env;
- generated config;
- logs;
- exception messages;
- profile serialization;
- MCP/CLI responses.

## 6. Falsifiability

Cada gate crítico debe disponer de una mutación/canary que lo haga rojo. Ver `GATE-TEETH.md`.

