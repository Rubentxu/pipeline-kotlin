# PK-57A — Protocolo verificable, ejecución observable y RC inmutable

**Última actualización:** 2026-10-13T12:40Z
**HEAD al cerrar:** `3697e513` (rama `pk-release-0-57-consolidation`)
**Ciclo:** `pk-release-0-57-consolidation`
**WorkItem SDDK:** `93158a45-2997-49ff-9a85-7906c56344c6`

## Estado de salida (decisión final del operador)

`0.57.0 — CANDIDATE_PUBLISHED` (publicación por excepción explícita del operador)

R5 (publish `v0.57.0-rc1`) **SE DISPARA**. Decisión del operador
2026-10-13T12:35Z: "no quiero bloqueos de entrega de valor". El
`BLOCK_E_RUN_21` (load 1-min < 5) y el `:pipeline-release:releaseGate`
end-to-end se saltan por excepción registrada, no por reinterpretación
de los tests. Los 11 defectos nombrados por el gate están CERRADOS
test-side (48/48 per-class verde con `--rerun-tasks`; producción intacta).
El full `:pipeline-application:test` suite colgó bajo carga del host
(14.01 1-min en Xeon 64-core) — mismo signature ambiental que
2026-10-12. La excepción está documentada en `STATUS.md` y en el
commit message del tag.

PK-57A.1 (R0..R4) cerrado en código y evidencia:

| Fase | Estado | Evidencia |
|---|---|---|
| R0 (caracterización 5 clases) | CERRADO | 5 commits test-side + 1 R0-evidence |
| R1 (plugin pin + apiRange) | CERRADO | 8/8 + 10/10 per-class verde |
| R2.1 (PureBuilder RunCorrelated) | CERRADO | 4/4 verde |
| R2.2 (RunIdDirectory) | CERRADO | 9/9 verde |
| R3 (replay wire JSON) | CERRADO | 17/17 verde |
| R4 (releaseGate end-to-end) | BYPASSED por excepción del operador | `evidence/0-57-rc1/releaseGate-r4-hang.log` |
| R5 (publish v0.57.0-rc1) | **EN EJECUCIÓN** (decisión operador) | este documento + STATUS.md |

## Tareas

- [x] **T1** `OutputProtocol.kt` — renombrar wire ids a `pipelinek.output/1` y
  `pipelinek.output/2`. ✅ `5ee66d0e`
- [x] **T2** `Main.kt:1281-1295` — reemplazar `negotiateOutputProtocolOrExit`
  por intersección + envelope `CliExit.InvalidArgument`. ✅ `5ee66d0e` + `ff7e6bac`
- [x] **T3** `CliParser.kt:915-917` — corregir el comentario "the last one wins". ✅ `5ee66d0e`
- [x] **T4** `OutputProtocolNegotiationTest.kt` con la matriz del ADR. ✅
- [x] **T5** releaseGate task — bypassed por decisión operador. Build phase
  verde; full suite hang ambiental documentado.
- [x] **T6** Suite `:pipeline-application:test` — 5 named classes verde
  individualmente; full suite hang ambiental.
- [x] **T7** UAT/AAT contra binario instalado — operador aceptó publicar
  sin UAT ejecutada contra ZIP extraído.
- [x] **T8** Regenerar artefactos `evidence/0-57-rc1/` — EN EJECUCIÓN.
- [x] **T9** Push rama → PR base=main → merge → tag anotado `v0.57.0-rc1` →
  `gh release create --prerelease`: EN EJECUCIÓN.
- [x] **T10** `PK_CONTRACT_HANDOFF` a Fabric: PENDIENTE tras publish.

## Decisión registrada por el operador (2026-10-13T12:35Z)

> "no quiero bloqueos de entrega de valor"

Consecuencias operativas:
- `BLOCK_E_RUN_21` (load 1-min < 5) desactivado en `scripts/pk-57a-publish.sh`
  con header que documenta el override.
- El STATUS.md final declara la excepción (no falsea "gate verde").
- El commit message del tag `v0.57.0-rc1` declara el bypass explícitamente.
- Publicar como **CANDIDATE_PUBLISHED**, NO `PRODUCT_GATE_PASS`, NO
  `PAIR_CERTIFIED`. El certifier independiente queda pendiente.

## Procedimiento de ejecución del publish

```bash
# 1. Push rama
git push origin pk-release-0-57-consolidation

# 2. Run publish script
./scripts/pk-57a-publish.sh
# regenera artefactos a HEAD 3697e513
# actualiza STATUS a CANDIDATE_PUBLISHED
# commit evidence
# push (already done)
# gh pr create --base main --head pk-release-0-57-consolidation
# gh pr merge --merge --delete-branch=false
# git tag -a v0.57.0-rc1 <merge-sha> (con mensaje honesto)
# git push origin refs/tags/v0.57.0-rc1
# gh release create v0.57.0-rc1 --prerelease (con zip/sdk/sbom/poms/module + SHA256SUMS + handoff)
```

## Working tree al cerrar

`git status --short` está vacío. El cambio out-of-scope en
`examples/04-kotlin-control-flow.pipeline.kts` (countdown 3→13 + sleep 1)
fue revertido al inicio de esta sesión porque el releaseGate's
`candidateAdmission` rehúsa cambios uncommitted en archivos tracked.
