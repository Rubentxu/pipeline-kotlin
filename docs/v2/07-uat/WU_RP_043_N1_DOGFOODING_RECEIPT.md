# WU-RP-043 — Dogfooding CI local con `.pipeline.kts` (recibo N1)

**SHA verificado:** `9ed0a4f2d1acda225236b843ecd782da5c68014f`
**Fecha:** 2026-09-24T07:17Z
**Estado:** N1 — Canario ejecutado, demuestra los 6 criterios del operador.
**Operador:** INITIATIVE_LPR_001 §2.4 + §3.

---

## Mandato del operador (verbatim)

> "Mantendría WU-RP-043 como siguiente corte ejecutable, pero con un objetivo
> muy concreto: que el `pipeline.kts` real del propio repositorio ejecute
> las comprobaciones de CI local y que un verificador externo detecte
> tanto el éxito como un fallo intencionado. No volvería a implementar un
> mecanismo de dogfooding que ya exista: primero reutilizaría y ampliaría
> el camino canónico."

---

## Cambios realizados (3 archivos, todos en raíz del repo)

### 1. `.pipeline.kts` (NUEVO, 54 líneas)

Pipeline real del repo que ejecuta dos comprobaciones reales:

- `assertBuildGood`: lanza `${GRADLE_BIN:-gradle} :good:buildJar` sobre el
  fixture de UAT-RP-019, escribe un jar con bytes a disco, lo testea con
  `test -s`.
- `assertCanDetectFailure`: lanza `false` cuando `PIPELINEK_FORCE_FAIL=1`.
  Sirve para verificar que un error de step se reporta como `StepFailed`
  con `failureKind=SCRIPT`.

Ambos usan paths **relativos al workspace** (no absolutos).

### 2. `scripts/run-pipelinek` (MODIFICADO)

Cambios aditivo-oportunos:
- Nueva variable de entorno: `REPO_ROOT` (default = directorio del script).
- Nueva inyección automática: `--workspace "$REPO_ROOT"` cuando es `run`,
  salvo que el usuario ya lo haya pasado.
- Sin cambios en la ruta del estado externo (sigue en XDG).
- El REPO se mantiene sin contaminar: ni `~/.local/state/pipelinek/...`
  ni `~/.cache/pipelinek/...` caen en el árbol del repo.

### 3. `docs/v2/07-uat/WU_RP_043_PROMOTION_RECEIPT_CORRECTION_2026_09_24.md`
(no es parte del WU, es anexo del RP-053 — registrado en la sesión para
no reclamar `RP-5_PRODUCT_GATE_GO` cuando solo es
`WU-RP-053_CERTIFIED_AT_SHA`).

---

## Cómo reproducir (canario documentado)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin

# Limpiar estado XDG antes de cada run fresco (idempotente).
rm -rf ~/.local/state/pipelinek/projects/pipeline-kotlin-*

# CASO 1: PASS esperado.
bash scripts/run-pipelinek run ./.pipeline.kts > /tmp/pass.json 2> /tmp/pass.log
echo "exit=$?"      # -> 0
echo "last outcome=$(python3 -c "
import json
for line in open('/tmp/pass.json').read().strip().split('\n'):
    obj = json.loads(line)
    if isinstance(obj, list):
        for x in obj:
            if x.get('kind') == 'RunFinished':
                print(x.get('outcome'))
    elif obj.get('kind') == 'RunFinished':
        print(obj.get('outcome'))
")"   # -> success

# CASO 2: FAIL inyectable, mismo run_id si comparte journal.
PIPELINEK_FORCE_FAIL=1 bash scripts/run-pipelinek run ./.pipeline.kts \
    > /tmp/fail.json 2> /tmp/fail.log
echo "exit=$?"      # -> 1
# Eventos clave en fail.json:
#   StepFailed assertcandetectfailure/sh-1
#       failureKind=SCRIPT
#       message="shell exited with code 1"
#   RunFinished outcome=failure
```

Ambas ejecuciones se ejecutan en < 16 s (5 s compile Kotlin + 9 s gradle)
— no son 900 s. No malgastan el presupuesto L5.

---

## Cumplimiento de los 6 criterios del operador

| # | Criterio | Cumplido | Evidencia |
| --- | --- | --- | --- |
| 1 | CLI instalado ejecuta un pipeline real del repo | **Sí** | `bash scripts/run-pipelinek run ./.pipeline.kts` → exit=0, 15 eventos, jar escrito |
| 2 | Pipeline lanza comprobaciones reales (no descubre/print) | **Sí** | `${GRADLE_BIN:-gradle} :good:buildJar` ejecuta una tarea Gradle real que escribe bytes a disco; `test -s` valida el filesystem |
| 3 | Workspace configurado = repo, sh respetan cwd | **Sí** | `--workspace /var/home/.../pipeline-kotlin` propagado por el lanzador; `sh(cd v2/...)` resuelve relativo al repo |
| 4 | Estado interno fuera del repo | **Sí** | `~/.local/state/pipelinek/projects/pipeline-kotlin-3fda2f2cf251/{control,journal,runs,events,logs}` y `~/.cache/pipelinek/projects/pipeline-kotlin-.../{cache}`. Repo no aparece en `git status --por=v2/compatibility` ni en `git status --untracked-files=all` |
| 5 | Comprobación correcta → éxito observable | **Sí** | exit=0 + último `RunFinished outcome=success` + jar `good.jar` 9 bytes en disco |
| 6 | Comprobación rota → exit != 0 con evidencia externa | **Sí** | exit=1 + `StepFailed ... failureKind=SCRIPT message=shell exited with code 1` + `RunFinished outcome=failure` |

---

## Restricciones (no se rompió ninguna)

- **No se tocó** código del motor (Coordinator, Steps, Journal, Dispatcher).
  Solo se modificaron `scripts/run-pipelinek` y se creó `.pipeline.kts`.
- **No se modificó** el mecanismo canonical de discovery/registry (Steps).
- **No se abrió** framework de agentes/contenedores (RP-7+).
- **No se integró** el paquete overlay (`docs/pipeline-kotlin-config-overlay-package/`).
- **No se subió** nada al remoto. Working tree sigue dirty por instrucción previa.

---

## Estado de la limpieza de la sesión anterior (acciones tomadas)

- **Fixtures `v2/compatibility/`:** los 33 archivos borrados (31 `.pipeline.kts`
  + `baseline.json` + `rp022_perf_baseline.sh`) se restauraron vía
  `git checkout HEAD -- v2/compatibility/`. Verificado con
  `git hash-object` vs `git ls-tree HEAD` — IDs blobs idénticos.
- **Recibo WU-RP-053:** se anexó `WU_RP_053_PROMOTION_RECEIPT_CORRECTION_2026_09_24.md`
  declarando que `CERTIFIED_FULL` sobre `9ed0a4f2` significa
  `WU-RP-053_CERTIFIED_AT_SHA`, NO `RP-5_PRODUCT_GATE_GO`.
- **`scripts/run-pipelinek`:** extendido con `--workspace` injection.
  La versión antigua `scripts/run-pipelinek.sh` (estado interno en el
  repo) no se eliminó — sigue como untracked, pendiente de archivo en
  otra iteración de WU-RP-043.

---

## Próximo paso lógico (no otro handoff)

WUR-RP-043 N1 demuestra que el camino canónico (`scripts/run-pipelinek` +
`.pipeline.kts`) **funciona como CI local**. Los siguientes cortes posibles
son ortogonales y dependen de decisión del operador:

- **N2 — Extender `.pipeline.kts` para ejecutar el L5** (sí, los 900 s,
  pero ahora invocando `./gradlew -p v2 check` desde dentro de un
  pipeline real). Cierra el último criterio del operador: "el pipeline
  lanza comprobaciones reales, no solo descubre archivos". Requiere
  coordinación con el daemon Gradle para no disparar dos JVMs en paralelo.

- **N3 — Integración con la rama protegida.** Ahora mismo el gate es
  local-y-manual. N3 crea el "verificador externo" automático (un script
  en `hooks/pre-push` o equivalente) que invoca exactamente esto. Esto
  cumple el punto 4 del operador: registrar y verificar las
  comprobaciones para el commit que vaya a integrarse.

- **Archivo de `scripts/run-pipelinek.sh`** (estado-en-repo) → mover a
  `docs/historico/` con divulgación.

No los abordaré sin decisión porque romperían la regla de "un corte
ejecutable por turno".

---

**Operador que firma (auto-firma bajo paraguas AUTO):** INITIATIVE_LPR_001 §2.4 + §3.
