# UAT Acceptance Matrix

| ID | Milestone | Critical | Automatizable | Human check |
|---|---|---:|---:|---:|
| UAT-M0-001 | M0 | yes | yes | no |
| UAT-COMP-001/002 | M1 | yes | yes | developer error UX |
| UAT-DSL-001 | M2 | yes | partial | yes |
| UAT-DSL-002..005 | M2/M3 | yes | yes | partial |
| UAT-STEP-* | M2 | yes | yes | no |
| UAT-EVT-* | M1/M3 | yes | yes | no |
| UAT-REC-* | M3/M4 | yes | yes | operator review |
| UAT-PROT-* | M4 | yes | yes | no |
| UAT-K8S-* | M5 | yes | yes | platform review |
| UAT-CRED-* | M5 | yes | partial | security review |
| UAT-SEC-* | M5/M9 | yes | partial | security review |
| UAT-JENKINS-* | M6 | yes | partial | UI/admin review |
| UAT-PLUGIN-* | M7 | yes | yes | developer review |
| UAT-E2E-001 | M7 | yes | partial | yes |
| UAT-GRAPH-* | M8 | no/yes by feature | yes | query UX |
| UAT-SC-* | M8 | yes | yes | security review |
| UAT-CHAOS-* | M9 | yes | yes | operator review |
| UAT-PERF-* | M9 | yes | yes | architecture sign-off |

## Release acceptance

### Alpha
M0-M3 critical UAT pass.

### Beta
M0-M7 critical UAT pass; known P2/P3 documented.

### RC
All critical M0-M9 pass; no P0/P1.

### GA
RC + soak/migration UAT + release/rollback rehearsal.
