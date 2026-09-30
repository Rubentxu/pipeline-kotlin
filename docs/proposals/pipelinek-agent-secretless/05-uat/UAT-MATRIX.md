# UAT Matrix

| ID | Escenario | Expected |
|---|---|---|
| ASX-COMP-001 | Legacy `sh("echo ok")` pre/post | mismo contrato canonical |
| ASX-COMP-002 | Legacy `sh` replay | no efecto duplicado |
| ASX-COMP-003 | Legacy `withCredentials(string...)` | misma env binding/event semantics |
| ASX-COMP-004 | Legacy failure/timeout | mismo FailureKind/outcome |
| ASX-PLAN-001 | strong provider + raw provider | selecciona strong |
| ASX-PLAN-002 | min STRONG, sólo raw | admission fail-closed |
| ASX-PLAN-003 | audience mismatch | deny antes de materialización |
| ASX-PLAN-004 | profile conflict | typed admission error |
| ASX-SCOPE-001 | profile scope con 2 Steps | lease independiente por Step |
| ASX-SCOPE-002 | timeout | revoke + cleanup |
| ASX-SCOPE-003 | cancel | revoke + cleanup |
| ASX-PROV-001 | local store | usable con posture declarada |
| ASX-PROV-002 | Secret Service locked | actionable fail-loud |
| ASX-PROV-003 | ASV signer/proxy | agente nunca recibe secret bytes |
| ASX-PROV-004 | env compat | RAW posture visible |
| ASX-PROJ-001 | npm overlay | 0600, selected via userconfig, removed |
| ASX-PROJ-002 | Maven overlay | `-s` temp, removed |
| ASX-PROJ-003 | Gradle overlay | config privada, cache preservada |
| ASX-PROJ-004 | curl netrc fallback | no token argv, file removed |
| ASX-PROJ-005 | Git SSH | signer socket; no private-key export |
| ASX-INLINE-001 | command args spaces | exact argv semantics after shell encoding |
| ASX-INLINE-002 | command durable failure | same core.sh outcome |
| ASX-INLINE-003 | arbitrary registered Step | same handler/codec as pipeline |
| ASX-INLINE-004 | external plugin Step | discovered via normal plugin seam |
| ASX-INLINE-005 | run in repo | zero new repo files |
| ASX-OUT-001 | raw child prints secret canary | store/filter output redacted |
| ASX-OUT-002 | custom filter | cannot observe raw stream |
| ASX-OUT-003 | agent output | deterministic compact JSON |
| ASX-FLOW-001 | 3-step transient flow | same coordinator/event contracts |
| ASX-FLOW-002 | failure mid-flow | canonical failure semantics |
| ASX-SEC-001 | plugin requests undeclared credential capability | denied |
| ASX-SEC-002 | provider reports stronger posture than implementation fixture | gate detects mismatch |
| ASX-SEC-003 | overlay cleanup disabled mutation | test fails |
| ASX-SEC-004 | redaction moved after filter mutation | test fails |

