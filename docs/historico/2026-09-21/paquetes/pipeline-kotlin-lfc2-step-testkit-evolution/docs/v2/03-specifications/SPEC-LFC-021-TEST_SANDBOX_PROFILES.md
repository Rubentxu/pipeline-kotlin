# SPEC-LFC-021 — Test sandbox profiles

**Status:** proposed

## Relationship

SPEC-LFC-014 sigue siendo autoridad del producto.

Esto define fidelidad de testing.

## Profiles

```text
T0 PURE
T1 IN_PROCESS
T2 FORKED_LOCAL
T3 RESTARTABLE_LOCAL
T4 ROOTLESS_CONTAINER
T5 SERVICE_CONTAINER
T6 ONLINE_SMOKE
```

## T4 baseline

Primer adapter recomendado: Podman rootless.

Defaults:

- rootless;
- UID no-root;
- workspace mount explícito;
- rootfs read-only cuando sea posible;
- tmpfs `/tmp`;
- capabilities Linux reducidas;
- no host PID;
- no host network por defecto;
- resource limits;
- PID limit;
- external timeout;
- logs before teardown.

## T5 services

Servicios desechables:

- Git server;
- HTTP test server;
- PostgreSQL;
- artifact repository;
- object-store emulator.

Imágenes preferiblemente pinned by digest en CI.

Readiness por probes + deadline.

## Future capability mapping

Ejemplos:

```text
no NetworkCapability -> no egress
WorkspaceRead -> read-only mount
WorkspaceWrite -> rw mount
```

Es evolución futura; la declaración de capability es obligatoria desde ahora.

## Security scenarios

- no orphan process tras cancel/timeout;
- secrets no persistidos;
- temp credential files removidos;
- env no filtrado fuera de scope;
- denied capability fails before adapter invocation;
- no owned containers after teardown.

## Reproducibility

Devbox + Just permanece para toolchain.

Podman es overlay de aislamiento, no reemplazo universal.
