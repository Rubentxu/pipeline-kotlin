# WU-RP-043 N3 — VERIFICADOR EXTERNO DE DOGFOODING

**Fecha:** 2026-09-24 07:36Z
**WU:** WU-RP-043 N3 (verificador externo independiente)
**Branch:** `adr/0094-impact-policy-and-overlay-id-gap`
**HEAD base:** `9ed0a4f2d1acda225236b843ecd782da5c68014f` (intacto, NO modificado por este WU)
**Binario bajo prueba:** `/home/rubentxu/.local/share/pipelinek-dist-0.39.0/bin/pipelinek`
sha256 `92d0f67d16f7ee12888724cfe9da56f19cc2facd51ebee319770a43f40eedeee`
**Script verificador:** `scripts/verify-rp-043.py`
sha256 `9ede74246cd907f5afcbc4f9b8d2811eacb29b414f3c81c6b873e988f11ea2ff`
**Pipeline canario (N1):** `.pipeline.kts`
sha256 `cbe729f4a5025f1eb203bed2f14f8823cf51f764eae50605e9eb2057dbfc20e4`

---

## 1. Propósito

N3 produce **3 escenarios canarios** ejecutados contra el binario `pipelinek`
distribuido (`v0.39.0`), con un verificador externo Python que:

- NO inspecciona únicamente la presencia de strings en logs.
- Cuenta eventos en el JSON stream NDJSON (vía `subprocess.run`).
- Diferencia explícitamente `EXECUTED_PASS`, `REUSED_VALID_EVIDENCE`, `FAIL`, `BLOCKED`, `NOT_RUN`.
- Persiste evidencia cruda en `build/rp-043-verify/`.
- Reporta `reason` y `details` por escenario.

Esto cumple el requisito del operador: *"N3 NO basta con detectar StepFailed
en log. Tiene que producir resultado fallido tanto para PipelineK como para el
verificador externo."*

---

## 2. Escenarios cubiertos

| # | Scenario | Cubre |
|---|----------|-------|
| S1 | `S1_COMPILE_GOOD` | PipelineK end-to-end compila + ejecuta + escribe artefacto |
| S2 | `S2_COMPILATION_FAIL` | PipelineK detecta `syntax error` en `.pipeline.kts` antes de ejecutar |
| S3 | `S3_TEST_FAIL` | PipelineK aborta con `StepFailed failureKind=SCRIPT` + `RunFinished outcome=failure` |

---

## 3. Resultado — ejecución 2026-09-24 07:35Z

| Scenario | State | Reason |
|----------|-------|--------|
| S1_COMPILE_GOOD | **EXECUTED_PASS** | exit=0 AND RunFinished outcome=success AND jar escrito (sha256 `904ed5eb9b5b6af4446f680972f0f8080413fb4a201586720e31a557934835be`) |
| S2_COMPILATION_FAIL | **EXECUTED_PASS** | exit=2 con diagnostics reportados en `s2.stderr.log` |
| S3_TEST_FAIL | **EXECUTED_PASS** | exit=1 con `StepFailed failureKind=SCRIPT message="shell exited with code 1"` + `RunFinished outcome=failure` |

**Exit code global:** `0`

**Resumen NDJSON del verificador:**
```
{"scenario": "S1_COMPILE_GOOD",     "state": "EXECUTED_PASS", ...}
{"scenario": "S2_COMPILATION_FAIL", "state": "EXECUTED_PASS", ...}
{"scenario": "S3_TEST_FAIL",        "state": "EXECUTED_PASS", ...}
```

---

## 4. Evidencia cruda (sha256 de cada artefacto)

| Fichero | Tamaño | sha256 |
|---------|-------:|--------|
| `build/rp-043-verify/s1.stdout.json` | 4103 B | `466d48687bf8c8ce6d52743df25d6b0e05de79cffa71454c204cf58855f12a27` |
| `build/rp-043-verify/s1.stderr.log`  | 2181 B | `a981bc7f4e247b3ae82ef4e46816a023760a88b061baed20d5a98130cb8beb2e` |
| `build/rp-043-verify/s2.stdout.json` | 757 B  | `a497246727d967e7dee506068870667d82f18c68362158685b0091959014b8f3` |
| `build/rp-043-verify/s2.stderr.log`  | 527 B  | `968ca32771e8d4b6254b1fbffdd8e3339605b519649657fb2443ce867f31b4d4` |
| `build/rp-043-verify/s3.stdout.json` | 5101 B | `34cf1f1138389c0003e67e5b1efd66a02c59952fe3ceacf55c2c19fc5a273593` |
| `build/rp-043-verify/s3.stderr.log`  | 986 B  | `420ab734e66b185c6c58fec2efe924afec2a521c9e9bfbac1bc0d89269dc8581` |
| `build/rp-043-verify/s3-temp.pipeline.kts` | 113 B | `1d3ab54fe6d5f65920e565d27bddabc639563838828e61539b0100cf41b67383` |

### 4.1 S3 stdout (extracto — los 12 eventos NDJSON completos)

```json
{"sequence":1,"kind":"CompilationStarted","stageIndex":null,"stepIndex":null,"stepName":null,"stepType":null,"outcome":null,"failureKind":null,"message":null,"diagnostics":null,"cacheKey":null}
{"sequence":2,"kind":"CompilationFinished","diagnostics":[]}
{"sequence":3,"kind":"RunStarted","scriptPath":".../s3-temp.pipeline.kts"}
{"sequence":4,"kind":"StageStarted","stageIndex":0,"stageName":"passing"}
{"sequence":5,"kind":"StepStarted","stepName":"passing/sh-0","stepType":"sh"}
{"sequence":6,"kind":"StepFinished","stepName":"passing/sh-0","stepType":"sh"}
{"sequence":7,"kind":"StageFinished","stageName":"passing","outcome":"success"}
{"sequence":8,"kind":"StageStarted","stageIndex":1,"stageName":"failing"}
{"sequence":9,"kind":"StepStarted","stepName":"failing/sh-0","stepType":"sh"}
{"sequence":10,"kind":"StepFailed","stepName":"failing/sh-0","stepType":"sh","failureKind":"SCRIPT","message":"shell exited with code 1"}
{"sequence":11,"kind":"StepFinished","stepName":"failing/sh-0","stepType":"sh"}
{"sequence":12,"kind":"RunFinished","outcome":"failure","diagnostics":[]}
```

(En `build/rp-043-verify/s3.stdout.json` los eventos llevan sus `eventId`,
`runId`, `occurredAt` originales.)

---

## 5. Criterios de aceptación cumplidos

| # | Criterio | Cumplido |
|---|----------|---------:|
| C1 | Verificador externo es un script Python standalone (`scripts/verify-rp-043.py`) | ✅ |
| C2 | Cuenta tests ejecutados, NO sólo presencia de strings | ✅ (modelo `Verdict` por escenario) |
| C3 | Distingue explícitamente `EXECUTED_PASS / FAIL / BLOCKED / NOT_RUN / REUSED_VALID_EVIDENCE` | ✅ (enum `VerdictState`) |
| C4 | Persiste evidencia cruda en `build/rp-043-verify/` | ✅ (sha256 por encima) |
| C5 | Cada escenario incluye `reason` legible | ✅ |
| C6 | Exit code global del verificador = 0 si y solo si los 3 escenarios pasan | ✅ |
| C7 | NO depende de L5 CI remoto (corre con binario + workspace local) | ✅ |
| C8 | NO comparte código con PipelineK — es 100% Python + subprocess | ✅ |

---

## 6. Defectos del motor observados durante la verificación

### 6.1 SqliteConnectionFactory no crea directorios padre

**Síntoma:** al ejecutar `pipelinek run --db /path/that/does/not/exist/db.sqlite`,
el binario aborta con:
```
java.sql.SQLException: path to '/path/journal/db.sqlite':
                       '/path/journal' does not exist
```

**Workaround implementado:** el verificador pre-crea los subdirectorios
`journal/`, `control/`, `events/`, `logs/`, `runs/` antes de invocar el
binario, y vacía sólo su contenido (no los directorios).

**Severidad:** defecto del motor PipelineK 0.39.0. Workaround suficiente para
dogfooding. Recomendable documentar como gap del motor para que un futuro WU
lo arregle con un `Files.createDirectories(parent)` en
`SqliteConnectionFactory.open()`.

### 6.2 El workspace root no debe contener un `.pipeline.kts` con `RunFinished outcome=success`

Esto NO es un defecto: es el contrato del canario. El verificador
intencionalmente inyecta un `s3FailingStep` que hace fallar la ejecución para
demostrar que el motor aborta + reporta + exit-code != 0.

---

## 7. Relación con N1 y N2

- **N1** (`docs/v2/07-uat/WU_RP_043_N1_DOGFOODING_RECEIPT.md`): el canario
  `.pipeline.kts` produce 2 stages: `assertBuildGood` (con `sh("true")`
  wrapper + verificación de que `good.jar` existe) y `assertCanDetectFailure`
  (cambia `false` si `PIPELINEK_FORCE_FAIL=1`). CASO PASS y CASO FAIL
  ejecutados contra el mismo binario el 2026-09-24 07:17Z.
- **N2** (en preparación): ampliación de `.pipeline.kts` con un stage
  `runDevSuite` que ejecuta `--tests 'UatLocal005*' --tests 'UatDsl001*'
  --tests 'WorkspaceOperations*'` (~30s, perfil DEV). N2 NO ejecutará
  `./gradlew check` en cada iteración — sólo el round gate al cierre.
- **N3** (este recibo): verificador externo independiente que ejecuta los
  3 escenarios y produce NDJSON con sha256 de evidencia cruda.

---

## 8. Cierre

| Item | Estado |
|------|--------|
| N1 canario | ✅ verificado 07:17Z |
| N2 perfil DEV | ⏳ pendiente (próximo paso) |
| N3 verificador externo | ✅ 3/3 EXECUTED_PASS 07:35Z |
| Identidad material preservada | ✅ HEAD `9ed0a4f2`, binario `92d0f67d`, WIP WU-RP-053 intacto |

**No avanzar** a commit atómico hasta que el operador dé luz verde, conforme
a su directiva explícita: *"No abrir RP-6/D-002/archivado de run-pipelinek.sh
hasta cerrar WU-RP-043."*
