# Artifacts & Software Supply Chain Specification

## 1. Objetivo

Que outputs de CI/CD sean entidades verificables y trazables, no únicamente ficheros adjuntos a un build.

## 2. Artifact record

Campos:
- artifactId;
- name/type;
- content digest;
- size;
- storage URI/ref;
- media type;
- producer run/stage/step/attempt;
- source commit;
- worker/runtime/plugin digests;
- createdAt;
- SBOM refs;
- provenance ref;
- signature refs.

## 3. Upload

Worker sube directamente a S3/MinIO/Artifactory/registry mediante scoped capability/presigned request. Controller recibe `ArtifactPublished` metadata; no proxya binarios grandes.

## 4. Provenance

Registrar al menos:
- source repo + immutable commit;
- pipeline source digest;
- DSL/runtime/compiler versions;
- plugin lock digest;
- worker image digest;
- relevant input artifact/image digests;
- output digests;
- actor/trigger identity;
- timestamps.

## 5. SBOM

SBOM es entidad asociada al artifact, idealmente SPDX/CycloneDX adapter-neutral. Policies pueden exigirla antes de `DEPLOYED_TO` production.

## 6. Signatures

El dominio modela signature/attestation references sin acoplarse a una herramienta concreta. Signing key material nunca pasa por graph/event payload.

## 7. Policy examples

- Production artifact must be signed.
- Production artifact must have SBOM.
- Build must originate from protected branch/commit policy.
- Plugin and worker images must be allowed digests.
- Deployment must reference immutable artifact digest.
