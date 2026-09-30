# Patch propuesto para `pipeline-kotlin/AGENTS.md`

Fusionar conceptualmente estas reglas en el AGENTS actual; no crear autoridad paralela.

## FAST-LANE RELEASE CANDIDATE LAW

`pipeline-kotlin` is the fast lane. External certification MUST NOT stall independent product work.

After publishing an immutable candidate:

1. register its candidate handoff;
2. continue the next independent WorkItem immediately;
3. do not wait for harness completion before continuing main;
4. a later candidate may supersede older uncertified candidates of the same release train;
5. a harness FAIL creates corrective work but does not rewind main or invalidate unrelated later commits.

## PRODUCER / CERTIFIER SEPARATION

This repository owns product construction. It MUST NOT duplicate the external harness's expensive assurance matrix.

Producer-owned gates are limited to:

- affected tests during development;
- repository full suite at candidate boundary;
- reproducible package;
- SBOM/checksums;
- cheap exact distribution identity;
- minimal installed smoke.

Real-project matrices, multi-manager installation, kill/restart, heavy concurrency, adversarial security, external toolchains and performance certification belong to `pipelinek-release-harness`.

## TARGET-VERSION CANDIDATE LAW

Candidate status is NOT part of ProductVersion.

A candidate for target version `V` MUST contain product identity `V` in:

- asset filename;
- archive root;
- application JAR manifest;
- runtime `pipelinek version`;
- distribution manifest.

Candidate identity is material SHA-256 plus release-train metadata.

## PRODUCT IDENTITY LAW

For every candidate:

```text
ProductVersion == AssetVersion == ArchiveRootVersion == EmbeddedVersion == RuntimeVersion
```

Mismatch is a build/release defect and blocks candidate handoff.

## IMMUTABLE DISTRIBUTION MANIFEST

The distribution manifest describes the built artifact and MUST remain byte-immutable with the candidate.

Downstream certification or promotion MUST NOT rewrite product version fields.

## CANDIDATE HANDOFF

The cross-repo handoff MUST include at least:

- candidateId (ZIP SHA-256);
- releaseTrain;
- candidateSequence;
- productVersion;
- sourceCommit;
- artifact name/SHA/size/archive root;
- distribution manifest SHA;
- SBOM SHA.

## HARNESS FINDING INTAKE

External findings are consumed asynchronously.

A reproducible blocking defect:

- gets one deduplicated issue/fingerprint;
- is prioritized by severity;
- is fixed on main;
- is verified by the harness on a later candidate.

No old candidate is mutated or retagged.
