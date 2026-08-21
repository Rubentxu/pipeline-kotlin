# Security Architecture

## Threat model resumido

Actores/riesgos:
- pipeline source malicioso o comprometido;
- plugin malicioso;
- worker comprometido;
- secretos filtrados;
- replay que repite side effects;
- worker antiguo enviando eventos tras perder lease;
- artifact tampering;
- supply-chain del propio runtime/plugin.

## Principios

1. Controller nunca ejecuta user code V2 como camino normal.
2. Script classpath es explícito y mínimo; prohibido `wholeClasspath=true` en producción.
3. Java Security Manager no se considera sandbox.
4. ClassLoader isolation es aislamiento de dependencias, no frontera de seguridad.
5. Sandbox fuerte se delega a OS/container.
6. Secrets no forman parte del event model.
7. Plugins y worker images se identifican por digest y, cuando proceda, firma.

## Worker sandbox

Baseline Kubernetes:
- non-root;
- read-only root filesystem cuando sea viable;
- `allowPrivilegeEscalation=false`;
- drop capabilities;
- seccomp `RuntimeDefault`;
- resource requests/limits;
- network policy/egress policy;
- service account mínimo;
- workspace/volumes explícitos;
- namespace/pod security standards.

Hardening opcional para multitenancy:
- gVisor;
- Kata Containers;
- microVM/Firecracker-like workers;
- dedicated node pools.

## Credentials

Flujo preferido:

```text
Step requests CredentialRef
      ↓
Policy authorizes usage
      ↓
Provider creates short-lived CredentialLease
      ↓
Projection local to worker
      ↓
redaction + revocation/expiry
```

Preferir workload identity/OIDC/CSI antes de transferir secret bytes.

## Protocol security

- mTLS worker↔gateway objetivo;
- worker identity ligada a WorkerLease;
- protocol version negotiation;
- message size limits;
- rate limits;
- sequence monotónica;
- fencing token en eventos que mutan run state;
- payload schemas estrictos.

## Supply chain

Cada run registra digests de:
- pipeline source;
- compiler/scripting adapter;
- runtime image;
- plugins;
- input container images;
- output artifacts.

SBOM/provenance/signature son entidades del modelo, no attachments opacos.
