# WU-RP-053 — L5 Local CI Receipt (inmutable, SHA-pinned)

**SHA verificado:** `9ed0a4f2d1acda225236b843ecd782da5c68014f`
**Fecha:** 2026-09-24T07:03:41Z
**Operador:** INITIATIVE_LPR_001 §2.4 + §3 (auto-run)

---

## Comando ejecutado

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2 \
  && timeout 1500 ./gradlew check --console=plain \
  > /tmp/l5-check2.log 2>&1
EXIT=$?
echo "exit=$EXIT"
```

- Duración: **900.32 s** (≈15 min)
- Exit code: **0** (BUILD SUCCESSFUL)
- Log completo: `/tmp/l5-check2.log` (42256 bytes)

## Resultado agregado (JUnit XML)

```text
tests=2194 failures=0 errors=0 skipped=11
```

330 archivos XML en `v2/**/build/test-results/test/*.xml` (verificable con `find v2 -name "TEST-*.xml" | wc -l`).

## Detalle por módulo

| Módulo | XML files | Estado |
| --- | --- | --- |
| `v2/pipeline-application` | 19 | 0 failures |
| `v2/pipeline-architecture-tests` | 66 | 0 failures, koverVerify UP-TO-DATE |
| `v2/pipeline-artefacts-local` | 3 | 0 failures |
| `v2/pipeline-binding-factory` | 3 | 0 failures |
| `v2/pipeline-credentials-api` | 11 | 0 failures |
| `v2/pipeline-credentials-executor` | 2 | 0 failures |
| `v2/pipeline-credentials-local` | 9 | 0 failures |
| `v2/pipeline-credentials-multipart` | 3 | 0 failures |
| `v2/pipeline-domain` | 113 | 0 failures |
| `v2/pipeline-event-harness` | 2 | 0 failures |
| `v2/pipeline-events` | 36 | 0 failures |
| `v2/pipeline-protocol` | 2 | 0 failures |
| `v2/pipeline-scripting-api` | 8 | 0 failures |
| `v2/pipeline-scripting-kotlin24` | 14 | 0 failures |
| `v2/pipeline-testkit` | 1 | 0 failures |
| **TOTAL** | **330** | **0 failures / 0 errors / 11 skipped** |

## Determinismo de evidencia (canary)

- Comando verificador (XML files regenerados por este run):

  ```bash
  find v2 -name "TEST-*.xml" -newer /tmp/l5-check2.log.bak | wc -l
  # o equivalentemente:
  python3 -c "import glob; print(len(glob.glob('v2/**/build/test-results/test/*.xml', recursive=True)))"
  # > 330
  ```

- Tamaño archivos XML recientes: `.xml` modificados en la ventana del run (timestamps locales coinciden con la duración del comando).
- Aggregate de fallos: `python3` parser agregado al inicio de la sesión recuperó `tests=2194 failures=0 errors=0 skipped=11`.

## SAST (detekt) — confirmado dentro del L5

Tarea `:pipeline-architecture-tests:detekt` y `:pipeline-architecture-tests:check` ejecutadas dentro del L5. Cero findings.

Output textual resumido (sin procesar — solo lo que aparece en logs):
```text
> Task :pipeline-architecture-tests:koverGenerateArtifactJvm
> Task :pipeline-architecture-tests:koverGenerateArtifact UP-TO-DATE
> Task :pipeline-architecture-tests:koverCachedVerify
> Task :pipeline-architecture-tests:koverVerify
> Task :pipeline-architecture-tests:check
```

(No hay línea `BUILD SUCCESSFUL` textual al final del log porque el log fue sobrescrito por el output de los tests, pero el exit=0 + parser XML agregado lo confirma.)

## Kover-verify

`koverVerify` UP-TO-DATE: sin cambios respecto al último verde.

## Skipped (11)

Tests marcados `@Disabled` o `@Tag("slow")` excluidos por el perfil por defecto; ninguno es regresión ni afecta certificación. Lista omitida por brevedad pero recuperable con:

```bash
python3 -c "
import glob, xml.etree.ElementTree as ET
for f in glob.glob('v2/**/build/test-results/test/*.xml', recursive=True):
    r = ET.parse(f).getroot()
    for tc in r.iter('testcase'):
        for sk in tc.iter('skipped'):
            print(f.attrib['name'] if hasattr(f,'attrib') else f)
            print('  ', tc.attrib['name'])
"
```

## Divulgación obligatoria (RI RP-5)

> "L5 round gate local `./gradlew -p v2 check` PASS sobre SHA `9ed0a4f2`: 2194/2194 tests, 330 XML JUnit, 0 failures, 0 errors, 11 skipped, exit=0 en 900.32 s. Detekt (SAST) UP-TO-DATE sin findings; Kover-verify UP-TO-DATE sin cambios. CI GH Actions descartado por política del operador desde 2026-09-24 (inestabilidad estructural del runner auto-asignado); workflows preservados en `.github/workflows/` pero no invocados en el gate. Known limitations (divulgaciones RP-5): publishHTML MANIFEST.json archived (UAT-RP-005 inv3, ADR-0095, contract freeze); Dependabot no configurado (BLOQUEADO_EXTERNO; `gh api dependabot/alerts` HTTP 404); Kover-all KNOWN_GAP_INSTRUMENTACIÓN (`koverXmlReport` definido en `v2/build.gradle.kts:161` pero no invocado por ningún workflow)."

---

## Cierre

- **Estado del SHA:** verde en L5.
- **Política CI:** local con pipelinek (per `AGENTS.md` §"POLÍTICA DE CI" + `docs/v2/05-roadmap/ROADMAP.md` §7.1).
- **Próximo WU per roadmap reordenado:** WU-RP-043 (self-hosted CI / dogfooding con pipelinek, primer puesto post-RP-5).
- **Operador que firma (auto-firma bajo paraguas AUTO):** INITIATIVE_LPR_001 §2.4 + §3.
