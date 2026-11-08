# Zip Slip Defense

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `security`

**Expected outcome:** `failure`

## What it exercises

The trust boundary: whose bytes a plugin is allowed to run

*The example says this about itself:*

> Utilities OFFICIAL_PLUGIN live fixture.
> End-to-end defence-in-depth proof of containment on
> BOTH sides of the zip chain. The fixture:
> 1) Creates a benign zip archive (via the typed zipFiles Step).
> 2) Replaces the local-file-header entry-name bytes with a
> parent-segment name "../escaped.txt" using a 'core.sh' one-liner.
> The body / CRC remain valid (we just renamed the entry), so the
> resulting file is parseable as a real zip; only the entry name
> has been smuggled.
> 3) Runs 'core-utils.unzip' against the malicious archive.
> 4) The handler MUST reject the archive with a typed USER failure
> containing "Zip Slip". The pipeline as a whole fails with a
> typed failure.
> Note: this fixture intentionally triggers a typed failure. The launcher
> expects it: the run must fail, and it must fail with that specific
> refusal.

## The stages and the steps

### 1. `zip-slip-defense`

- `sh` — `echo 'benign' > abcdefghijklmn`
- `zipFiles` — `path = "build/utils/malicious.zip", paths = listOf("abcdefghijklmn"), overwrite = true,` — a step this plugin provides (`core-utils`)
- `sh` — `""" python3 - <<'PY' import struct p = 'build/utils/malicious.zip' data = bytearray(ope...`
- `unzip` — `path = "build/utils/malicious.zip"` — a step this plugin provides (`core-utils`)

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| stderr contains | `Zip Slip` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/security/zip-slip-defense/zip-slip-defense.pipeline.kts
pipelinek run      examples/catalogue/features/security/zip-slip-defense/zip-slip-defense.pipeline.kts
```

## Its lineage

- **Historical name:** `28-zip-slip-defense.pipeline.kts`
- **Origin path:** `examples/security/28-zip-slip-defense.pipeline.kts`
- **Body digest:** `sha256:8f3dff8f373dfee29c87c04a04b620e73ad5eb970a49d84d47b62acd5d3ca089`
