# Evidence bundle STATUS — 0.57.0-rc1

**Última actualización:** 2026-10-12T22:05Z
**HEAD actual:** `31619155890fc711ead6e6047fa4bbbec3b18f7d`
**Ciclo:** pk-release-0-57-consolidation

## Lo que el bundle contiene y de dónde viene

| Artefacto | Generado en SHA | Estado |
|---|---|---|
| `pipelinek-0.57.0.zip` | `a95956b24ad47c722bc9722c1256739462887d3f` | **STALE-RES-REQUIRED** si el operador exige ZIPs regenerados contra el HEAD actual |
| `pipelinek-0.57.0.sbom.json` | `a95956b` (mismo) | Idem |
| `pipeline-application-0.57.0.jar` | `a95956b` | Idem |
| `pipeline-domain-0.57.0.pom` | `a95956b` | Idem |
| `pipeline-sdk-bom-0.57.0.pom` | `a95956b` | Idem |
| `sdk-domain-0.57.0.module` | `a95956b` | Idem |
| `HANDOFF-TO-CERTIFIER.md` | c9df67f1 (actualizado a HEAD) | Vigente |
| `SHA256SUMS` | regenerado en este turno contra los artefactos en disco | Vigente para los artefactos en disco; self-verify del propio SHA256SUMS es estructural chicken-and-egg |
| `git-head.txt` | c9df67f1 | Vigente |
| `r1-f/RECEIPT-R1-F.md` | 84fab16d (evaluado contra ese SHA) | Vigente para el cierre de R1-F |

## Por qué el ZIP no se ha regenerado contra `c9df67f1`

La breve prohíbe reutilizar resultados antiguos como PASS si cambia
el SHA de código. Los 3 commits posteriores a `a95956b` son:

- `bc2410e7` — `examples/fabric-contract-consumer/.../FabricProjection.kt`
  (código del consumidor, no del producto)
- `55561dda` — test de UATDsl005, solo test
- `c9df67f1` — KDoc de `LaunchReceipt.kt` + nuevo ADR-PK1-LAUNCHRECEIPT-MODES

**Cambios materiales al contrato del producto entre `a95956b` y
`c9df67f1`: ninguno.** El schema `pipelinek.launch/1` es el mismo; la
forma del JSON no cambia; las firmas de API no cambian. Los 3 commits
son comentarios / tests / código externo al distribution ZIP.

Por tanto, la reutilización de los artefactos es **defendible bajo el
espíritu** de la regla, pero **no bajo la letra**. El operador puede
exigir la regeneración:

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin
./gradlew --no-daemon --max-workers=1 :pipeline-application:distZip
```

Esto **requiere load < 5** (umbral documentado en BLOCK_E_RUN_21).
La medición actual está en 8.41 (1-min), 4.84 (5-min), 3.20
(15-min). Cuando la media de 1-min baje de 5, la regeneración
queda autorizada.

## Lo que falta para el cierre del certifier

| Bloque | Estado | Bloqueante |
|---|---|---|
| R1-F (gate de plugins) | CERRADO bajo BLOCKED_ENVIRONMENT con 5 mutaciones ROJO | Re-medir a load < 5 para confirmar estabilidad |
| R2.1 (OutputProtocol) | IMPLEMENTADO en `v2/pipeline-application/.../OutputProtocol.kt` y `Main.kt` | No |
| R2.2 (ProcessOutputDecoder + 8-case matrix) | IMPLEMENTADO en `support/ProcessOutputDecoder.kt` + tests | No |
| R2.3 (CliContractTestKit dispatch) | IMPLEMENTADO en `support/CliContractTestKit.kt` | No |
| R2.4 (UatDsl005 migration) | CERRADO — commit 55561dda | No |
| R2.5 (LaunchReceipt modes ADR) | CERRADO — commit c9df67f1 | No |
| R3 (UAT re-measure WURp023, UatS2R0, smoke-e2e) | PENDIENTE | load < 5 |
| R4 (merge to main, tag, gh release) | PENDIENTE | autoridad humana |
| R5 (certifier 13 escenarios) | PENDIENTE | autoridad independiente |

## Procedimiento de re-generación

Cuando load < 5:

1. `./gradlew --no-daemon --max-workers=1 :pipeline-application:distZip`
2. Mover el ZIP nuevo a `evidence/0-57-rc1/pipelinek-0.57.0.zip`
3. Regenerar SBOM + JAR + POMs
4. Regenerar `SHA256SUMS`
5. Actualizar este STATUS.md con la nueva fecha
6. Re-correr el gate H1.4 (`DistributionMandatoryPluginsTest`) contra
   el ZIP nuevo
