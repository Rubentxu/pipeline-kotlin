# Spec: Test Sandbox Profiles

Authority: ADR-0072 (HF4 Rootless / HF5 Service), ADR-0048 (SandboxProfile.LOCAL), ADR-0053
(smoke E2E). Reconciled from the reference package `SPEC-LFC-021` (input only). Detail/design:
`openspec/changes/lfc2-step-constitution-plugin-seam/`.

## Purpose

Define the sandboxed execution profiles used by the higher HF levels, reusing the canonical sandbox
authorities rather than a second taxonomy.

## R1 — Profiles

- **HF4 Rootless Sandbox**: Podman/hardened isolation, rootless; maps to `SandboxProfile.LOCAL`
  (ADR-0048) and extends it for isolation/security.
- **HF5 Service Sandbox**: HF4 plus isolated Git/HTTP/DB/artifact services (ADR-0053).
- **HF6 Online Smoke**: real OSS/network ecosystem compatibility.

## R2 — Isolation guarantees

Unique workspace, control root, stores, credential store, plugin dir, run ID, process group per run;
teardown kills descendants and preserves diagnostics; env deny-list and PATH normalization per
ADR-0048.

## R3 — Real services

Credentials, Git/HTTP/DB and artifact services run isolated inside the sandbox; nothing reaches the
host network unless HF6 requires it.

## Acceptance

- Kill/restart/resume and security/adversarial scenarios run at HF4/HF5.
- No in-process test is silently escalated to a container (criterion: minimum faithful level).
