# WU-LPR-080 — PipelineK SDKMAN publication receipt (in progress)

**Authority**: WU-LPR-080 (SDKMAN publication + user documentation)
**Date opened**: 2026-09-19
**Status**: **IN PROGRESS** — awaiting external SDKMAN response

---

## Status classification (mandatory)

| Dimension | Status | Evidence |
|---|---|---|
| `GITHUB_RELEASE` | **GREEN** | <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0> — ZIP, sha256, sbom, manifest; publishedAt `2026-09-19T09:28:28Z`; `isDraft=false`, `isPrerelease=false` |
| `SDKMAN_CANDIDATE` | **WAITING_EXTERNAL** | Vendor onboarding email sent to `info@sdkman.io` on 2026-09-19 (Gmail id `1a0b8fd0db9ad3fe`); awaiting reply on the current registration procedure. The historical PR path (`sdkman/sdkman-db-migrations`) is **read-only** per its README — explicitly "no longer accepting pull requests" while the Mongo→Postgres migration lands. |
| `SDKMAN_VERSION` | **BLOCKED** | Cannot publish a version until `pipelinek` is registered as a candidate and `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN` are issued. |
| `SDKMAN_DEFAULT` | **BLOCKED** | Cannot promote a version to default without first publishing and UAT-verifying it. Hard rule: **a published version that fails `sdk install` MUST NOT be promoted.** |
| `SDKMAN_CLEAN_INSTALL` | **BLOCKED** | No candidate yet. UAT script `scripts/release/sdkman-install-uat.sh` is ready and hardened (PATH init, noninteractive prompts, real checks on binary/lib presence). |
| `USER_DOCS` | **IN PROGRESS** | README + `docs/user/*` being authored in this cycle. Cheat sheet and Quickstart will be UAT-tested against the published binary before sign-off. |
| `LPR_GATE_1` | **GREEN on GitHub channel; NOT_YET_CLOSED overall** | The GitHub Release channel is closed. SDKMAN channel must close before LPR-GATE-1 can be declared fully closed. Receipt `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md` already records this distinction. |

---

## Defects observed in `pipelinek 0.39.0` (must be fixed before LPR-GATE-1 is fully closed)

### Exit-code propagation (verified empirically by `scripts/release/cheat-sheet-uat.sh`)

| Scenario | Expected exit | Observed (v0.39.0) |
|---|---|---|
| `run` on a passing script (fresh DB) | `0` | `0` ✓ |
| `run` on a failing script (fresh DB) | `1` | `0` — **DEFECT** |
| `run` on a failing script (after success in same DB, different script path) | `1` | `0` — **DEFECT** |
| `run` on a failing script (after success in same DB, `--rerun`) | `1` | `1` ✓ |
| `validate` on a malformed script | non-zero | `0` — **DEFECT** |
| `pipelinek` (no args) | non-zero | `0` — **DEFECT** |
| Unknown flag | non-zero | `0` — **DEFECT** |

**Impact**: shell scripts gating on `$?` cannot reliably distinguish
success from failure in `0.39.0`. Users must gate on the `RunFinished.outcome`
field of the NDJSON event stream instead.

**Until fixed**, the cheat sheet and CLI reference document the
defect and provide the NDJSON-based gate as the recommended pattern.

This is a release-gating defect. A follow-up release with the fix is
required before LPR-GATE-1 can be declared fully closed.

---

## What is done in this cycle

### ZIP compatibility with SDKMAN (verified empirically)

Against the guide at <https://github.com/sdkman/sdkman-cli/wiki/Well-formed-SDK-archives>:

```
pipelinek-0.39.0.zip                  ← matches ${candidate}-${version}.zip
└── pipelinek-0.39.0/                 ← mandatory base dir, matches convention
    ├── bin/pipelinek                 ← UNIX executable (preserved bit)
    ├── bin/pipelinek.bat             ← Windows .bat (ignored on UNIX)
    └── lib/*.jar                     ← runtime jars
```

Checks performed on the ZIP downloaded from the live GitHub Release:

- ✅ Top-level entry is `pipelinek-0.39.0/` (base dir, not loose files)
- ✅ `bin/pipelinek` exists inside the base dir (1 match)
- ✅ `lib/*.jar` exists inside the base dir (multiple jars)
- ✅ `unzip -t` reports no errors
- ℹ️ No `LICENSE` text file in the archive. The SDKMAN guide states the `bin/` folder is the **only** mandatory directory; `license` is described as "can contain some info text files" — not required. **No ZIP rebuild is needed for SDKMAN compatibility.**

### Scripts (`scripts/release/`)

| Script | Commit | What it does |
|---|---|---|
| `sdkman-publish.sh` | `e9494f7d` + hardening | POST `https://vendors.sdkman.io/release` with `{candidate, version, url, platform: UNIVERSAL, checksums.SHA-256}`. Hardened with: SHA sidecar ↔ recompute cross-check, explicit `UNIVERSAL` platform. Idempotent (per SDKMAN docs). Fail-closed without credentials. |
| `sdkman-install-uat.sh` | `d661073e` + `9d281d25` + `9484168d` | Mandatory gate between publish and default. Sources SDKMAN init, prepends `current/bin`, answers interactive prompts with `Y\nY\n`. Validates version/doctor/validate/run/`sdk current`/bin+lib presence. Uninstalls at end. Fail-closed without `sdk` CLI. |

### Handoff (`docs/v2/07-uat/WU_LPR_071_SDKMAN_RESUME_PROTOCOL.md`)

Captures the canonical state, exact commands, stop-conditions, hard rule, after-state close instructions, and what NOT to do.

---

## What is blocked externally

### `SDKMAN_CANDIDATE` — WAITING_EXTERNAL

**Action taken**: Vendor onboarding email sent to `info@sdkman.io` from the connected Gmail account on 2026-09-19 (Gmail id `1a0b8fd0db9ad3fe`).

**Email content** (confirmed by the user):

> Hello SDKMAN! team,
>
> I'm the maintainer of PipelineK, an open-source, local-first CI/CD tool that executes pipelines defined using a Kotlin DSL.
>
> Project repository: <https://github.com/Rubentxu/pipeline-kotlin>
>
> We would like to register `pipelinek` as a new SDKMAN! candidate and publish our JVM-based distribution through your Vendor API.
>
> We noticed that the SDKMAN database migrations repository is now read-only while the project transitions to PostgreSQL.
>
> Could you please confirm the current onboarding procedure for registering a new candidate, and let us know whether any additional information is required?
>
> We would also like to request access to the Vendor API so that we can automate future releases from our GitHub Actions workflow.
>
> Our intended candidate configuration is:
>
> - Candidate identifier: `pipelinek`
> - Display name: `PipelineK`
> - Distribution: `UNIVERSAL`
> - Runtime requirement: Java 21
> - Distribution format: ZIP
> - Release source: GitHub Releases
>
> I have attached my armored public GPG key for secure delivery of the Vendor API credentials.
>
> Thank you for your help.
>
> Best regards,
> Rubentxu
> PipelineK maintainer

**Pending external action (user responsibility)**:

- Receive SDKMAN reply with the current onboarding procedure (it may no longer be a PR to `sdkman/sdkman-migrations`, which is read-only per its README).
- If SDKMAN asks for a GPG public key, export it from the user's keyring and send through the channel SDKMAN indicates.
- Receive `SDKMAN_CONSUMER_KEY` + `SDKMAN_CONSUMER_TOKEN` (encrypted reply).

**What the agent will do once credentials arrive**:

1. Run `SDKMAN_CONSUMER_KEY=… SDKMAN_CONSUMER_TOKEN=… ./scripts/release/sdkman-publish.sh 0.39.0` (publishes the GitHub Release ZIP as the SDKMAN candidate version, no promotion to default).
2. Run `./scripts/release/sdkman-install-uat.sh 0.39.0` on a runner with `sdk` CLI installed (the mandatory UAT gate).
3. If UAT passes, run the `PUT /default` one-liner (provided in the handoff protocol).
4. Refresh this receipt with publish/UAT/default receipts and links, and finally close `LPR_GATE_1` to **CLOSED**.

---

## What is in progress (this cycle)

### User documentation

Pending items (in order):

- [ ] `README.md` rewrite: replaces the current "not ready for release" framing with the post-release user-facing overview. Answers: what is PipelineK → requirements → install with SDKMAN → first pipeline → docs → supported capabilities / v1 limits.
- [ ] `README.es.md` (Spanish mirror of the above).
- [ ] `docs/user/installation.md` (Linux/macOS + WSL on Windows; Java 21 requirement; SDKMAN install + manual fallback for GitHub Releases).
- [ ] `docs/user/quickstart.md` (first pipeline, end-to-end).
- [ ] `docs/user/cli-reference.md` (verified against `pipelinek --help` on the released binary; not visual-review-only).
- [ ] `docs/user/pipeline-dsl.md` (verified against the certified `local-core-v1` ledger examples).
- [ ] `docs/user/configuration-and-workspace.md` (`--workspace`, `--db`, `--control-root`).
- [ ] `docs/user/credentials-and-security.md` (secret redaction at the durable shell seam; how to inspect events without leaking).
- [ ] `docs/user/events-and-troubleshooting.md` (where to find transcripts; how to recover a durable run).
- [ ] `docs/user/upgrading.md` (SDKMAN channel upgrade; rolling back).
- [ ] `docs/user/cheat-sheet.md` (UAT-tested: extract and execute the relevant examples against the distributed binary, not visual review).

### Workflow hardening

Pending items:

- [ ] GitHub Actions workflow that triggers on `release: published`, downloads the ZIP, cross-checks SHA, and calls the publish + UAT scripts. Pin third-party actions to immutable commits.
- [ ] Secret hygiene: `SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN` only in GitHub Actions secrets; never echoed, logged, or written to artifacts.

---

## Hard rules

1. **BUILD ONCE / CERTIFY ONCE / PUBLISH SAME BYTES.** SDKMAN consumes the same ZIP that GitHub Release publishes. No rebuild for SDKMAN.
2. **A published version that fails `sdk install` MUST NOT be promoted to default.** Hard-coded in `scripts/release/sdkman-install-uat.sh` header.
3. **No fake progress.** Until SDKMAN replies, `SDKMAN_CANDIDATE = WAITING_EXTERNAL`, `SDKMAN_VERSION = BLOCKED`, `SDKMAN_DEFAULT = BLOCKED`, `SDKMAN_CLEAN_INSTALL = BLOCKED`. This receipt will not be edited to claim any of them are `GREEN` without observed evidence.
4. **No silent PR to read-only repos.** `sdkman/sdkman-db-migrations` is read-only per its README; not opened.
5. **No GPG keys generated on the user's behalf.** Only the user exports and sends a public key through the channel SDKMAN indicates.

---

## Provenance

- Trunk HEAD at receipt opening: `9484168d` (after UAT hardening round 2)
- All commits referenced above are pushed to `origin/main`
- 16 untracked witness bundle preserved (do not touch)
- Tags `v0.36.0` / `v0.37.0` / `v0.38.0` / `v0.39.0` intact

---

## Appendix: parallel diagnostic (WU-LPR-090)

On 2026-09-19 a parallel diagnostic spike was run to validate the SDKMAN
install protocol end-to-end against a local HTTP mirror, without
requiring official vendor credentials.

- **Status of this receipt unchanged.** This spike is **LOCAL_TEST_ONLY**
  for installation and `NOT_PROVEN` for official publication. It does
  not unblock the `WAITING_EXTERNAL` state above.
- **What it proves:** the technical protocol used by `sdkman-cli 5.23.0`
  to install a candidate is reproducible locally with HTTP, no TLS, no
  auth, no remote deployment. The canonical ZIP for `pipelinek 0.39.0`
  installs end-to-end through the real SDKMAN client.
- **What it does NOT prove:** official publication on the public SDKMAN
  catalog (`api.sdkman.io/2`); official installation from that catalog;
  upgrade between two real releases.
- **Failure mode documented:** SDKMAN post-installation hooks are
  `source`-d, not executed; an `exit` in a hook body kills the caller
  shell. Hooks must define `__sdkman_post_installation_hook` and not
  call `exit`.
- **Full evidence:** `docs/v2/07-uat/WU_LPR_090_SDKMAN_LOCAL_MIRROR_RECEIPT.md`
- **Reproducer:** `scripts/release/sdkman-uat/{mirror.py, run-spike.sh}`
  (committed in `3b986c01`; re-run on 2026-09-19 against the published
  code: PASS).
- **Use when credentials arrive:** flip `SDKMAN_CANDIDATES_API` and
  `SDKMAN_BROKER_API` in `run-spike.sh` to the public URLs and re-run.
  No other code change required.

The official publication channel (`vendors.sdkman.io/release` with
`SDKMAN_CONSUMER_KEY` / `SDKMAN_CONSUMER_TOKEN`) remains the only path
to put `pipelinek` on the public SDKMAN catalog. The local-mirror
spike is a diagnostic, not a substitute.

---

## References

- SDKMAN vendor API: <https://sdkman.io/vendors>
- SDKMAN vendor onboarding process: <https://github.com/sdkman/sdkman-cli/wiki/Vendor-onboarding-process>
- SDKMAN well-formed SDK archives: <https://github.com/sdkman/sdkman-cli/wiki/Well-formed-SDK-archives>
- `docs/v2/07-uat/WU_LPR_071_SDKMAN_RESUME_PROTOCOL.md` (handoff)
- `docs/v2/07-uat/WU_LPR_090_SDKMAN_LOCAL_MIRROR_RECEIPT.md` (parallel diagnostic spike)
- `docs/v2/07-uat/LPR_GATE_1_LOCAL_PRODUCTION_READY_0.39.0.md` (LPR-GATE-1 partial closure)
- GitHub Release v0.39.0: <https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.39.0>
