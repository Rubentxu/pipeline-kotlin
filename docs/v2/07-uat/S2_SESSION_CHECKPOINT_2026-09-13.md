# S2-A7/A8 SESSION CHECKPOINT — 2026-09-12/13 (auto-generated)

## Estado canónico al cierre
- main @ 51cd021f (remoto verificado). Counters 5/5/5.
- core.deleteDir: CERTIFIED (8º Step). G0-G8 completos. PRs #27(G4) #28(G5) #29(G6) #30(G7) #31(G8).
- certified set (8): echo, sh, error, sleep, file.writeFile, emit.event, isUnix, deleteDir.
- core.pwd: G5✅ G6✅ G7 BLOCKED (STRUCTURED_DSL_RUNTIME_RETURN_GAP, evolutivo LFC-2R2), CERTIFIED=false.
- residual (5): milestone, cleanWs, load, waitUntil, archiveArtifacts.

## PENDIENTE DE REVIEW AL REANUDAR (PRÓXIMA ACCIÓN #1)
- Branch LOCAL cycle/lfc2-e1-milestone-g4 (NO pushed, NO merged): milestone G4 flip.
  - Commits: 87d998ff (flip atómico + sibling tests) + 827ffae5 (receipt).
  - 5/5/5 → 4/5/5 (ids 4: cleanWs/load/waitUntil/archiveArtifacts).
  - Tests fresh: G4Fitness 8/0, ContractSuite 23/0, UnitTest 19/0, UatLocal013 4/0, S3 52/0, Lfc2 3/0. CLI canary SUCCESS.
  - Firewall OK: solo ids+snapshot+assertCurrentState siblings+receipt. Cero G5.
  - Pre-existing red: CanonicalDurableRunCoordinatorTest 12/26 idéntico base/head (no regresión).
  - Flujo al reanudar: push → PR → review usuario → GO merge. milestone pasa a registry-primary.

## Merge train (regla vigente)
- milestone = ÚNICO writer global autorizado. Tras G4 merge → G5 destructivo atómico (4/5/5→4/4/4) con GO separado → G6 → G7 installed → G8.
- Regla: no avanzar G5 mientras haya un Step sin cerrar en la train destructiva.

## Trabajo local EN PARALELO (branches/worktrees, sin push, listos para revisión en lote)
| Lane | Branch | Commits | Resumen |
|---|---|---|---|
| cleanWs G1-G3 | cycle/lfc2-e1-cleanws (worktree pipeline-cleanws) | 0c3103eb/6fd6c043/bdd4f81e | Candidate+diff 5/5+suite 23/23. Lowering resuelto: {"kind":"cleanWs","deleteDirs":b,"patterns":[[]]}. READY_FOR_G4 |
| archiveArtifacts G0+G1 | cycle/lfc2-e1-archive-artifacts (worktree pipeline-archive-artifacts) | 6eba03b6/71c7cf9b/291cb98d | 21/21. CORE_KEEP (Jenkins-verbatim). UatLocal009 base-SHA 13/7 capturado. Nota: 7 pins S2-A6/G4 ya rojos en base limpia (rule-16, set idéntico) |
| cert harness | cycle/lfc2-e1-cert-harness (worktree pipeline-cert-harness) | ee25c8d2/2c0d0c49/a6fe9b7c/940b859d | StepContractCertification DSL. Pilot deleteDir 665→297 líneas, 22/22, XML byte-idéntico |
| milestone preflight | cycle/lfc2-e1-milestone-g4prep (worktree pipeline-milestone-g4prep) | 52392097 | READY_FOR_G4 (ya consumido por el flip en curso) |
| BodyInvoker | cycle/lfc2-e1-bodyinvoker (worktree pipeline-bodyinvoker) | e2f24f04/e67307e4/e7015fdb | ADR-0081 draft + seam BodyInvoker/BodyRef/BodyOutcome en pipeline-domain |
| load spike | cycle/lfc2-e1-load-spike (worktree pipeline-load-spike) | 98a2d8dd | SPIKE-018: boundary + veredicto de lift |
| LFC-2R2 | cycle/lfc2-e1-r2-runtime-return (worktree pipeline-r2-runtime-return) | 5128ae93 | ADR structured runtime-return (desbloquea pwd) |
| waitUntil spike | cycle/lfc2-e1-wait-until (worktree pipeline-waituntil) | 5a229ae4 | Causa raíz WAITUNTIL_BODY_INVOKER documentada |
| wave2 prep | cycle/lfc2-e1-wave2-prep (worktree pipeline-wave2-prep) | 88b35174 | Inventario cleanWs/load/archiveArtifacts |

## Cola tras milestone G4 merge
1. push+PR+review+GO merge cycle/lfc2-e1-milestone-g4
2. milestone G5 (destructivo atómico, GO separado)
3. milestone G6 (cert harness puede reducir el suite) → G7 installed → G8 → luego cleanWs ocupa la train
4. waitUntil desbloqueo vía ADR-0081 BodyInvoker
5. Revisión en lote de las lanes paralelas de arriba

## Decisiones/metodología vigentes
- STOP entre cada gate/merge; GO explícito del usuario con verificación de SHA remoto.
- Zero-fabrication: XML fresh + sha256 canaries; truth = JUnit XML.
- G4: N→N-1 (ids); G5: (N-1)/N/N→(N-1)/(N-1)/(N-1); receipts docs-only en gates de evidencia.
- Flip G4 = commit atómico coherente (estado+tests hermanos); G5 = destructivo atómico.
- Worktrees sin gradlew: usar /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/gradlew -p v2.
- Caveats conocidos: CanonicalDurableRunCoordinatorTest 12/26 rojo pre-existente; 7 pins fitness S2-A6/G4 rojos en base (identificar al tocar S3).
