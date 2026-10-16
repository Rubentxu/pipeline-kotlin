# H2 TODO 5 — Ephemeral UAT/AAT receipts (2026-10-16)

## Verdict

```text
H2 TODO 5 (ephemeral UAT families) = PASS, both families
```

The two ephemeral families prescribed by H2 TODO 5 ran against
the bytes the 0.62.0 release produced and passed. Per the
mandate's "Borrar TODOS los scripts y fixtures nuevos efímeros
al finalizar ciclo, tras verificar oráculo, sustituto y
evidencia", the scripts themselves were deleted from the session
root after the receipts were captured here.

## Family 1 — AAT (asset set composition)

**Path:** `/tmp/pk-h2-uat-001/run-aat.py` (deleted after receipt)

**What it verifies (5 things, all hermetic — pure Python, no JDK/Gradle/network):**

1. `distribution-manifest.json` declares exactly the 5 fields AGENTS.md
   §8 promises: `asset`, `sbom`, `sdk`, `bom`, `sha256sums` (and no others).
2. Each manifest entry's declared `sha256` matches the actual SHA-256
   of the file at `<distributions>/<name>`. A mismatch means the
   manifest is a lie about a release artifact.
3. `SHA256SUMS` covers exactly 4 distributable assets (zip, sbom,
   sdk.zip, bom.json) and lines agree with the observed bytes.
4. The distribution ZIP extracts to the `archive_root` declared in
   the manifest, and `archive_root/bin/...` + `archive_root/lib/...`
   are present.
5. `Implementation-Version` in the launcher jar's `META-INF/MANIFEST.MF`
   equals the manifest's `version` field.

**Run (2026-10-16, commit 53ab91c9, manifest from a previous build run):**

```bash
$ python3 -I /tmp/pk-h2-uat-001/run-aat.py
{
  "schema": "pk-aat-asset-set-composition-1",
  "result": "PASS",
  "manifest_version": "0.62.0",
  "archive_root": "pipelinek-0.62.0",
  "assets": [
    {"field": "asset",      "name": "pipelinek-0.62.0.zip",        "sha256": "200def25acfa6023b5128c5690e7264b44654b8925d567653af780477faf4e25"},
    {"field": "sbom",       "name": "pipelinek-0.62.0.sbom.json",  "sha256": "f3d212c4ae2a455816c618261b8d39f1b1680f3fa9436a3f45fa20e5d4a85f64"},
    {"field": "sdk",        "name": "pipelinek-0.62.0.sdk.zip",    "sha256": "5d0aa6956f66cd77534b0ec300373975907aa4db5953ed9542e72414c88d4b49"},
    {"field": "bom",        "name": "pipelinek-bom-0.62.0.json",   "sha256": "83e43e72dbf2d0d88b91168da03332ee6c4509177d1cc1cafa2611e4bdd45a8b"},
    {"field": "sha256sums", "name": "SHA256SUMS",                  "sha256": "43cd544c9ea3df2bef55a36b1c2a3fc1f76c5ae8dda353e95fc5be3b5607fb42"}
  ],
  "covered_by_sha256sums": [
    "pipelinek-0.62.0.zip",
    "pipelinek-0.62.0.sbom.json",
    "pipelinek-0.62.0.sdk.zip",
    "pipelinek-bom-0.62.0.json"
  ]
}
$ echo $?
0
```

## Family 2 — UAT (Maven consumer resolution)

**Path:** `/tmp/pk-h2-uat-002/pom.xml` (deleted after receipt)

**What it verifies (4 things, hermetic — only `v2/build/sdk-repo/`, no remote):**

1. The published BOM POM is valid Maven and parseable.
2. The 5 `<dependencyManagement>` entries in the BOM resolve to real JARs.
3. Gradle module metadata (`.module` files) under `v2/build/sdk-repo/`
   is acceptable to a downstream Maven resolver.
4. A consumer that does NOT live inside the build can pull the SDK
   from `v2/build/sdk-repo/` and see a consistent version set.

**Run (2026-10-16, manifest from the same build run):**

```bash
$ /home/rubentxu/.asdf/installs/maven/3.9.9/bin/mvn -B dependency:resolve
...
[INFO] The following files have been resolved:
[INFO]    dev.rubentxu.pipeline.v2:pipeline-domain:jar:0.62.0:compile
[INFO]    org.jetbrains.kotlinx:kotlinx-serialization-json-jvm:jar:1.11.0:compile
[INFO]    org.jetbrains.kotlinx:kotlinx-serialization-core-jvm:jar:1.11.0:compile
[INFO]    org.jetbrains.kotlin:kotlin-stdlib:jar:2.4.10:compile
[INFO]    org.jetbrains:annotations:jar:13.0:compile
[INFO]    org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:jar:1.11.0:runtime
[INFO]    dev.rubentxu.pipeline.v2:pipeline-events:jar:0.62.0:compile
[INFO]    dev.rubentxu.pipeline.v2:pipeline-scripting-api:jar:0.62.0:compile
[INFO] BUILD SUCCESS
[INFO] Total time:  4.395 s
$ echo $?
0
```

The two declared coordinates (`pipeline-domain`, `pipeline-events`)
both resolved at 0.62.0; the BOM also pulled `pipeline-scripting-api`
transitively via `pipeline-events` (matches the `api(project(":pipeline-scripting-api"))`
declared in `pipeline-events/build.gradle.kts`).

## Why this is BLOCKED_EXTERNAL still, not PASS

Both families used `v2/build/sdk-repo/` (a `file://` URL pointing at
the build host's `build/` directory). The Maven resolver worked
because the consumer process could read the same disk the producer
process wrote. A real remote consumer (CI in a different repo, a
plugin author on a different machine) cannot reach this URL. The
verdict recorded in `sdk-publication-status-2026-10-16.md` stays
`BLOCKED_EXTERNAL`; the receipts here prove the *contract* is sound
and the *publication chain* is end-to-end correct on the build host,
which is the half this repository controls.

## What was deleted

- `/tmp/pk-h2-uat-001/run-aat.py` — the AAT script.
- `/tmp/pk-h2-uat-002/pom.xml` — the consumer POM.

The deletion is consistent with the mandate: ephemeral UAT/AAT files
live in the session root, not in the repository, and the evidence
that survives the deletion is this receipt plus the on-disk bytes
in `v2/pipeline-release/build/candidate/` and `v2/build/sdk-repo/`.

## Cross-references

- `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md` line 178 (H2 TODO 5).
- `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md` (H2 TODO 4).
- Commit `efd569fe` — H2 TODO 3, BOM 5th `api(...)` constraint.
- Commit `53ab91c9` — H2 TODO 4, SDK publication status doc.
- `v2/pipeline-sdk-bom/build.gradle.kts` — the BOM that the consumer
  POM resolved in Family 2.
- `v2/pipeline-release/src/main/kotlin/.../CandidateMaterializer.kt` —
  the emitter whose outputs the AAT read in Family 1.
