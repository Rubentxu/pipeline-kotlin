# WU-LPR-071 — Resume protocol (SDKMAN channel)

This file is the canonical handoff for the three remaining steps that close
the SDKMAN channel of LPR-GATE-1. Everything else is already merged on `main`
and reproducible from the receipts.

## Current state (verified at commit `31ca3400`)

- GitHub Release `v0.39.0` PUBLIC: <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0>
- ZIP SHA-256: `385b140c35f6f017d8077eb27d78964ddaf2bd5bd37c5e11afcae5671eb0cbb8`
- ZIP size: 91,416,100 bytes (91.4 MB)
- 4 tags intact: `v0.36.0`, `v0.37.0`, `v0.38.0`, `v0.39.0`
- Trunk clean: `0 modified`, `16 untracked` (witness `WU-LPR-000`, do not touch)
- Receipt: `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md`

## Three-step chain (in strict order)

```
publish   →   install UAT   →   promote to default
(e9494f7d)    (d661073e)        (PUT /default)
   ↑              ↑                  ↑
creds        publish OK          UAT PASS
```

### Step 1: publish (requires vendor credentials)

**Block**: `SDKMAN_CONSUMER_KEY` and `SDKMAN_CONSUMER_TOKEN` from the SDKMAN
vendor account `Rubentxu`. These are **not** in the repo, env, or
`~/.sdkman/etc/`. They live in the user's vendor onboarding.

Once available:

```bash
SDKMAN_CONSUMER_KEY=… \
SDKMAN_CONSUMER_TOKEN=… \
  ./scripts/release/sdkman-publish.sh 0.39.0
```

Expected output:
- `POST https://vendors.sdkman.io/release` → JSON 200
- `verify` block: `version visible: True`
- Final line: `✅ pipelinek 0.39.0 published to SDKMAN (UNIVERSAL).`

Stop-conditions:
- HTTP 401/403 → wrong credentials, do not retry blindly
- HTTP 422 → malformed payload (re-check ZIP URL and SHA-256)
- `version visible: False` after 200 OK → re-run after a few seconds (cache)

### Step 2: install UAT on a clean runner (requires published version)

The clean runner MUST have `sdk` CLI installed. The current dev machine
(`bazzite-rubentxu`) does **not** have `sdk` installed; install
[SDKMAN](https://sdkman.io/install) first, or run the script on a CI runner.

Once on a runner with `sdk` installed:

```bash
./scripts/release/sdkman-install-uat.sh 0.39.0
```

The script validates in order:

1. `sdk` CLI present
2. `sdk install pipelinek 0.39.0` noninteractive
3. `pipelinek version` reports `pipeline 0.39.0`
4. `pipelinek doctor` exit 0
5. `pipelinek validate trivial.kts` → `VALIDATION SUCCESSFUL`
6. `pipelinek run real-project fixture` → `outcome=success`
7. `sdk uninstall pipelinek 0.39.0` (clean teardown)

Hard rule (codified in script header and receipt):
**A published version that fails `sdk install` MUST NOT be promoted.**
If any check fails, fix the release and re-publish a new version; do not
promote a broken version to default.

### Step 3: promote to default (requires UAT PASS)

Only after Step 2 passes all checks, one line:

```bash
SDKMAN_CONSUMER_KEY=… \
SDKMAN_CONSUMER_TOKEN=… \
  bash -c '
    curl -X PUT \
      -H "Consumer-Key: ${SDKMAN_CONSUMER_KEY}" \
      -H "Consumer-Token: ${SDKMAN_CONSUMER_TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"candidate\":\"pipelinek\",\"version\":\"0.39.0\"}" \
      https://vendors.sdkman.io/default
  '
```

Verify:

```bash
curl -fsS https://api.sdkman.io/2/pipelinek | jq '.defaultVersion'
# → "0.39.0"
```

## After Step 3: close the receipt

Once `defaultVersion == "0.39.0"` on SDKMAN:

1. Edit `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md`:
   - Mark all three Outstanding items as done (`[x]`)
   - Add the SDKMAN publish receipt (date, payload digest, response code)
   - Add the SDKMAN UAT receipt (runner, all 7 checks PASS)
   - Update Section 10 provenance summary with the new commits
2. Commit: `docs(lpr): close SDKMAN channel — LPR-GATE-1 fully closed`
3. Push: `git push origin main`

After that push, LPR-GATE-1 is **fully closed across both channels**.

## What NOT to do

- ❌ Do not promote to default before running Step 2.
- ❌ Do not modify `951b3cb5` (the certified commit) — it is the ZIP source of truth.
- ❌ Do not move or reuse any tag (`v0.36.0`, `v0.37.0`, `v0.38.0`, `v0.39.0`).
- ❌ Do not touch the 16 untracked files (`docs/pipeline-kotlin-local-production-ready-2026-09-18/`).
- ❌ Do not recompile for SDKMAN — the SDKMAN binary is the same ZIP bytes from GitHub Release.

## References

- `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md` — closure receipt
- `docs/v2/07-uat/WU_LPR_071_RELEASE_PREP_RECEIPT.md` — round-gate defects + workspace fix
- `docs/v2/03-specifications/DISTRIBUTION_RELEASE_SPEC.md` §6, §7 — spec authority
- `scripts/release/sdkman-publish.sh` (e9494f7d) — Step 1
- `scripts/release/sdkman-install-uat.sh` (d661073e) — Step 2
- SDKMAN Vendor API: <https://sdkman.io/vendors>
