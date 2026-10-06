# Final milestone — V1 retirement and repository cleanup

**This milestone is mandatory and MUST be last.**

## 1. Goal

End with one architectural product line and a repository that does not expose obsolete V1 implementation, obsolete architecture documentation or scripts that invite accidental reuse.

Git history remains available; the working tree should describe the current product.

## 2. Why deletion is delayed

Until M9, V1 is useful for:

- behaviour comparison;
- migration archaeology;
- identifying product capabilities worth retaining;
- proving a V2 replacement is intentional rather than accidental loss.

After M9, the same material becomes a liability:

- duplicate names/mental models;
- accidental imports/dependencies;
- stale search results for agents/developers;
- obsolete docs competing with current roadmap;
- scripts that invoke unsupported paths.

## 3. Pre-delete gate

M10 cannot start until all are true:

1. Shared Libraries replacement certified or formally rejected.
2. V2 tooling/LSP/diagnostics replacement certified.
3. Plugin admission replacement certified.
4. Cache decision recorded.
5. Every V1 area has a disposition.
6. `V1_DEPENDENCY_COUNT = 0` from current production/build/distribution.
7. Full required gate green on same candidate.
8. Migration guide published in current docs.

## 4. Archaeology preservation outside the repo

Before deletion, create an immutable archive if the project wants point-in-time convenience beyond Git history:

```text
pipeline-kotlin-v1-archaeology-<source-sha>.tar.zst
SHA256SUMS
MANIFEST.json
```

Store it as a release asset/external archive, not as another directory in `main`.

The manifest records:

- source SHA;
- included paths;
- per-file SHA256;
- creation command/tool version;
- statement that the archive is historical and unsupported.

## 5. Candidate legacy code modules to remove

The exact deletion list is generated in M0 and revalidated in M9. Expected historical candidates include root-era modules such as:

- `core/` (V1 core, after verifying no current build use);
- `pipeline-backend/`;
- `pipeline-config/`;
- `pipeline-lsp-server/`;
- `pipeline-steps-system/`;
- obsolete V1 `pipeline-cli/` if distinct from current V2 distribution wiring;
- obsolete V1-only integration fixtures/modules;
- V1-only `lib-examples` or examples proven superseded;
- `.backup`, `.disabled` and abandoned source variants tied only to V1.

**Do not blindly delete by top-level name.** `examples/` also contains current V2/plugin/fabric contract consumers; delete by the inventory/disposition, not by pattern alone.

## 6. Legacy build cleanup

Remove:

- Gradle includes for deleted modules;
- obsolete dependency constraints/plugins required only by V1;
- V1 packaging tasks;
- native-image configs tied only to V1 binaries;
- stale distribution files/templates;
- dead test source sets;
- build logic branching on V1/V2 selection.

At exit there should be no "choose V1 vs V2" build concept.

## 7. Legacy scripts cleanup

Inventory and remove scripts that:

- build or launch V1;
- invoke deleted modules/classes;
- generate obsolete metadata;
- perform old release/CI flows;
- repair/migrate formats no longer supported;
- duplicate current SDDK/current tooling.

Keep only scripts with a current owner and test/caller.

## 8. Legacy documentation cleanup

Remove from the working tree:

- `docs/historico/` material classified as historical-only;
- obsolete V1 architecture documents;
- old roadmaps that can be reconstructed from Git;
- migration plans whose destination has completed and whose retained information is already in release notes/current docs;
- stale diagrams naming removed components;
- READMEs for deleted modules;
- docs that claim deprecated CLI/plugin/library behaviour.

The root/current docs index must point only at active authority and supported product docs.

A short current `HISTORY.md` may state major architectural eras and link to Git tags/releases; it must not carry the entire legacy tree forward.

## 9. Legacy CI/release cleanup

Remove or rewrite:

- workflows/jobs that build V1;
- release steps packaging V1 artifacts;
- test matrices for deleted modules;
- stale badges/check names;
- docs describing unsupported distribution channels.

## 10. Search gates

Create a closure script/test that fails on unapproved occurrences of:

- deleted Gradle project names;
- deleted V1 package prefixes;
- removed legacy entry points;
- deleted documentation paths;
- V1 binary names;
- V1-only plugin manager/service-locator classes.

Allowlist only intentional historical text in release notes if needed.

## 11. Repository hygiene gate

After deletion:

```text
git clean -xfd   # in disposable clone only
gradle full required gate
build distribution
install distribution
run UAT smoke
run AAT suite
scan docs links
scan build graph
scan package/module references
```

No cache from the pre-delete workspace may be required to pass.

## 12. Final receipt

Record:

- source/base SHA;
- final SHA;
- exact deleted paths;
- files retained and why;
- archaeology archive SHA256 if created;
- build/test commands and exits;
- distribution hashes;
- repository search results;
- known limitations;
- confirmation that no V1 runtime/build path remains.

## 13. Definition of success

> A new contributor or coding agent searching the repository sees one architecture, one roadmap, one CLI/runtime model and no attractive obsolete implementation to copy by accident.

## 14. Compiler optimization disposition at closure

M9 records phase/profile/v2 identity certification and separate memory/persistence/session GO or NO-GO. Any enabled optimization completes its applicable tests; a declined one is explicitly NOT_DELIVERED/NOT_APPLICABLE rather than a fake PASS. A V1 cache is never retained merely to simulate a pending optimization.

Revalidate post-delete compilation and installed cold/warm modes actually enabled on the exact new SHA. All unselected V1 cache/lifecycle code still receives a disposition and is retired through the same inventory. M10 remains mandatory and last.
