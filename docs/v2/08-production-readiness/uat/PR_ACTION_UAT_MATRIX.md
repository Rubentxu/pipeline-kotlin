# Action-Plan UAT Matrix

| ID | Action | Scenario | Observable oracle |
|---|---|---|---|
| UAT-PR-001 | PR-001 | generate current state twice | identical SHA256 |
| UAT-PR-002 | PR-001/002 | change repo HEAD without regenerating | validation fails |
| UAT-PR-003 | PR-003 | one UAT has two current states | generator/lint rejects |
| UAT-PR-004 | PR-004 | stacked workspace PRs | single explicit stack, no duplicate closure |
| UAT-PR-005 | PR-006 | admission verdict belongs to previous SHA | required check FAIL |
| UAT-PR-006 | PR-008 | full suite has N failures | disposition count == N |
| UAT-PR-007 | PR-009 | `dir` body fails | behavior matches frozen contract; cwd restore proven |
| UAT-PR-008 | PR-009 | replay HAR-007 scenario | no duplicate effects; event order stable |
| UAT-PR-009 | PR-010 | artifact manifest SHA differs from source candidate | candidate rejected |
| UAT-PR-010 | PR-011 | full candidate L5 | 0 mandatory failures/errors |
| UAT-PR-011 | PR-012 | inject secret fixture | gitleaks/observable scan catches it |
| UAT-PR-012 | PR-012 | SBOM generated | component inventory binds candidate artifact |
| UAT-PR-013 | PR-013 | coverage report | critical-package metrics available and current |
| UAT-PR-014 | PR-014 | surviving critical mutation | classified or killed |
| UAT-PR-015 | PR-015 | large output/soak | RSS within approved SLO |
| UAT-PR-016 | PR-016 | build candidate twice | artifact SHA256 identical |
| UAT-PR-017 | PR-016 | two external repos | both success; intentional failure returns non-zero |
| UAT-PR-018 | PR-016 | harness verdict | exact artifact digest PASS |
| UAT-PR-019 | PR-017 | lifecycle extraction | golden events/journal unchanged |
| UAT-PR-020 | PR-018 | body extraction | dir/retry/timeout/parallel outcomes unchanged |
| UAT-PR-021 | PR-019 | recovery extraction | kill/resume/divergence unchanged |
| UAT-PR-022 | PR-020 | shared-model change | semantic integration suite catches incompatible composition |
