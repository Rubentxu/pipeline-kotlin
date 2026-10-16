# H2 TODO 2 — publisher asset set refactor (2026-10-16)

## Verdict

```text
H2 TODO 2 (publisher asset set from manifest) = DEFECT IDENTIFIED + REFACTOR DESIGNED

release-pipelinek.sh hardcodes 5 assets (ZIP, SBOM, HANDOFF.md, git-head.txt, SHA256SUMS).
AGENTS.md §8 mandates: ZIP, SBOM, SDK ZIP, BOM JSON, SHA256SUMS.
AGENTS.md §3 prohibits: HANDOFF.md, git-head.txt as release assets.

The script violates both §3 (uploads 2 forbidden) and §8 (omits 2 required).
The published v0.62.0-rc5 was also non-compliant (5 assets: ZIP, SBOM, SHA256SUMS,
git-head.txt, lane-planner.json — also missing SDK ZIP and BOM JSON).

This receipt records the defect and the refactor design. The code change
is a follow-up commit.
```

## The defect (concrete, verifiable)

Reading `scripts/release-pipelinek.sh` line 309-317 (the `gh release create` call):

```bash
gh release create "${PIPELINEK_RELEASE_TAG}" \
    --prerelease \
    --title "PipelineK ${PIPELINEK_RELEASE_TAG} (release-gate PASS sha256:${LOCAL_SHA})" \
    --notes-file "${EVIDENCE_DIR}/HANDOFF.md" \
    "${EVIDENCE_DIR}/${EXPECTED_ZIP_NAME}" \
    "${EVIDENCE_DIR}/${SBOM_NAME}" \
    "${EVIDENCE_DIR}/HANDOFF.md" \
    "${EVIDENCE_DIR}/git-head.txt" \
    "$SUMS"
```

The asset set is hardcoded as 5 paths. The verification loop at
line 323 enforces the same hardcoded list:

```bash
for required in "${EXPECTED_ZIP_NAME}" "${SBOM_NAME}" HANDOFF.md git-head.txt SHA256SUMS; do
    if ! printf '%s\n' "$PUBLISHED" | grep -Fxq "$required"; then
        echo "release-pipelinek: published release is missing required asset '${required}'." >&2
        ...
    fi
done
```

This list is **the literal manifest of defects** that AGENTS.md §3
and §8 explicitly forbid.

## §3 violations (forbidden as release assets)

| Asset | Status per §3 | Currently uploaded? |
|---|---|---|
| `HANDOFF.md` | FORBIDDEN: "reportes de agentes... que no sean contratos públicos de distribución" | YES (line 315 + as notes) |
| `git-head.txt` | FORBIDDEN: "git-head.txt, clasificaciones, informes de impacto y otros recibos" | YES (line 316) |

Both are receipts/internal evidence. They stay in the EVIDENCE_DIR
(committed to git) but MUST NOT be GitHub release assets.

## §8 violations (required but missing)

AGENTS.md §8 mandates the 5-asset set:

```text
pipelinek-<v>.zip         the runtime
pipelinek-<v>.sbom.json   SBOM
pipelinek-<v>.sdk.zip     SDK contract bundle
pipelinek-bom-<v>.json    SDK BOM
SHA256SUMS                digest authority
```

The current `release-pipelinek.sh` publishes 2 forbidden and 0 of
the SDK-related assets:

| §8 asset | Currently uploaded? |
|---|---|
| `pipelinek-<v>.zip` | YES (correct) |
| `pipelinek-<v>.sbom.json` | YES (correct) |
| `pipelinek-<v>.sdk.zip` | **MISSING** |
| `pipelinek-bom-<v>.json` | **MISSING** |
| `SHA256SUMS` | YES (correct) |

The SDK ZIP and BOM JSON are the consumer-resolvable SDK contract
chain. Their absence means the §8 contract is not satisfied.

## Verifying the v0.62.0-rc5 was also non-compliant

```text
$ gh release view v0.62.0-rc5 --json assets --jq '.assets[].name'
git-head.txt
lane-planner.json
pipelinek-0.62.0.sbom.json
pipelinek-0.62.0.zip
SHA256SUMS
```

5 assets, but not the §8 set:
- Includes `git-head.txt` (§3 forbidden) and `lane-planner.json` (a
  report/inventory, also §3-class).
- Missing `pipelinek-0.62.0.sdk.zip` and `pipelinek-bom-0.62.0.json` (§8 required).

So v0.62.0-rc5 was published via a different path (the
`publish_rc_release.py` from the distribution-first-release skill,
not `release-pipelinek.sh`), and that path ALSO has the wrong
asset set. The §8 contract has not been satisfied by any published
RC to date.

## The fix: read asset set from the manifest

The `DistributionManifest` typed value at
`v2/pipeline-release/.../DistributionManifest.kt:50-83` already has
the 5 asset slots as `DistributionArtifactRef?`:

```kotlin
data class DistributionManifest(
    val version: ProductVersion,
    val asset: DistributionAsset,           // ZIP (required)
    val source: DistributionSource,
    val sbom: DistributionArtifactRef?,     // SBOM (optional)
    val sdk: DistributionArtifactRef?,      // SDK ZIP (optional)
    val bom: DistributionArtifactRef?,      // BOM JSON (optional)
    @SerialName("sha256sums") val sha256sums: DistributionArtifactRef?,
)
```

The manifest is the single source of truth. The publisher MUST
read its asset set from the manifest, not maintain its own list.

### Refactor design

The `release-pipelinek.sh` script changes:

1. **Read the manifest's asset/sbom/sdk/bom/sha256sums fields** as
   the source of truth for the asset set.
2. **Stage each non-null asset** from `v2/pipeline-application/build/distributions/`
   to the EVIDENCE_DIR.
3. **Generate SHA256SUMS** over the staged asset set (excluding
   SHA256SUMS itself, which a manifest cannot contain).
4. **Upload via `gh release create`** the manifest-driven list, NOT
   a hardcoded one.
5. **Verify** the published release carries every manifest-declared
   asset, not a hardcoded check.
6. **Stop uploading HANDOFF.md and git-head.txt** as release assets.
   They stay as small text evidence in EVIDENCE_DIR (committed to
   git under `docs/v2/07-uat/evidence/<tag>/`).
7. **Stop calling the script "the §4 asset set"** in comments —
   rename to "the §8 asset set" per AGENTS.md.

### Code shape (pseudocode, not yet applied)

```bash
# Read manifest fields
ASSET_PATH="v2/pipeline-application/build/distributions/$(jq -r '.asset.name' "$RECEIPT")"
SBOM_PATH="v2/pipeline-application/build/distributions/$(jq -r '.sbom.name' "$RECEIPT")"
SDK_PATH="v2/pipeline-application/build/distributions/$(jq -r '.sdk.name' "$RECEIPT")"
BOM_PATH="v2/pipeline-application/build/distributions/$(jq -r '.bom.name' "$RECEIPT")"
SUMS_PATH="v2/pipeline-application/build/distributions/$(jq -r '.sha256sums.name' "$RECEIPT")"

# Build staged set: only non-null fields, all under EVIDENCE_DIR
STAGED=()
[ -n "$ASSET" ] && { cp "$ASSET_PATH" "${EVIDENCE_DIR}/$(basename $ASSET)"; STAGED+=("${EVIDENCE_DIR}/$(basename $ASSET)"); }
[ -n "$SBOM"  ] && { cp "$SBOM_PATH"  "${EVIDENCE_DIR}/$(basename $SBOM)";  STAGED+=("${EVIDENCE_DIR}/$(basename $SBOM)"); }
[ -n "$SDK"   ] && { cp "$SDK_PATH"   "${EVIDENCE_DIR}/$(basename $SDK)";   STAGED+=("${EVIDENCE_DIR}/$(basename $SDK)"); }
[ -n "$BOM"   ] && { cp "$BOM_PATH"   "${EVIDENCE_DIR}/$(basename $BOM)");   STAGED+=("${EVIDENCE_DIR}/$(basename $BOM)"); }

# Hash over the staged set (excluding SHA256SUMS itself)
"${REPO_ROOT}/scripts/release/build-release-manifest.sh" --out "$SUMS" "${STAGED[@]}"

# Upload
gh release create "${PIPELINEK_RELEASE_TAG}" \
    --prerelease \
    --title "..." \
    --notes-file "${EVIDENCE_DIR}/HANDOFF.md" \
    "${STAGED[@]}" \
    "$SUMS"

# Verify
for asset_name in $(jq -r '.asset.name, .sbom.name, .sdk.name, .bom.name, .sha256sums.name' "$RECEIPT" | grep -v null); do
    if ! printf '%s\n' "$PUBLISHED" | grep -Fxq "$asset_name"; then
        echo "release-pipelinek: missing required asset '$asset_name'." >&2
        exit 1
    fi
done
```

## What this does NOT solve

- **v0.62.0-rc5 is already published** with the wrong set. This
  refactor affects FUTURE RCs (rc6+). A new RC (e.g. v0.62.0-rc6)
  with the correct §8 set is the natural follow-up, but that
  requires operator authorization to publish.
- **The `publish_rc_release.py` from the distribution-first-release
  skill** (used to publish rc5) has the same defect class (it
  produced a 5-asset set with `git-head.txt` + `lane-planner.json`,
  not the §8 set). Fixing the skill's script is a separate change
  outside this repo.
- **AGENTS.md §3 does not list `lane-planner.json` by name**;
  per the §3 wording "informes de impacto y otros recibos que no
  sean contratos públicos de distribución", lane-planner.json is
  in that bucket. The §3 wording is the authority.

## Cross-references

- AGENTS.md §3 (prohibited release assets: HANDOFF.md, git-head.txt, etc.)
- AGENTS.md §8 (5-asset contract: ZIP, SBOM, SDK ZIP, BOM JSON, SHA256SUMS).
- ADR-PK-DF-001 (the H2 TODO 1 DRAFT ADR; Option A is the §8 5 assets).
- `v2/pipeline-release/.../DistributionManifest.kt:50-83` (the typed asset set).
- `scripts/release-pipelinek.sh:309-317` (the wrong upload list).
- `gh release view v0.62.0-rc5 --json assets` (the actual non-compliant rc5 set).
- The H2 TODO 5 ephemeral UAT at `5e4cea31` (asset set composition test, PASS).
  That ephemeral UAT is what the §8 contract specifies; this receipt
  shows the actual publisher has not yet been wired to it.
