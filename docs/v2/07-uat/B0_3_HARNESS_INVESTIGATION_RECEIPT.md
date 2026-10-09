# B0-3 — Investigación del harness real antes de aceptar la premisa de G10

**Fecha:** 2026-10-09
**WorkItem SDDK:** `f8fc07e6-6f98-4b4a-81c0-3f5b717bd146` (Active)
**Estado:** investigación cerrada. La premisa que B0-F4 dio por falsa queda **corregida**: la capacidad existe, pero en un árbol que el remoto nunca verá.
**Alcance:** lectura del harness, sin escritura, sin push, sin credenciales inventadas.

Clases de evidencia:

```text
OBSERVED      comando ejecutado, salida citada
INFERRED      conclusión derivada de evidencia citada
NOT_VERIFIED  no medido; se dice explícitamente
```

---

## 1. Resumen

B0-F4 (2026-10-08) afirmaba, con evidencia de un checkout local del harness:

> la capacidad de publicar checks **no existe todavía**

Medido hoy, esa afirmación es **incompleta, no falsa**. Lo que ocurre es más preciso y peor:

```text
la capacidad de publicar checks EXISTE y está probada (53 tests verdes)
pero vive en un working tree sin commitear
que está 74 commits por detrás de su propio origin/main
y su origin/main está a su vez parado desde 2026-09-29
```

El resultado operativo para G10 no cambia: `NOT_RUN`, `RP-5 PRODUCT_GATE_STOP`. Pero la **causa** cambia, y la causa es accionable mientras que "no existe" no lo era.

---

## 2. Inventario de fuentes (OBSERVED)

| Fuente | Identificador | Estado |
|---|---|---|
| Harness remoto | `Rubentxu/pipelinek-release-harness` | PRIVATE, descripción `State: PROPOSED`, `pushedAt: 2026-09-29T15:02:06Z` |
| Harness remoto HEAD | `4431fb44` | `docs(evidence): incident report v0.43.0 promotion race` |
| Harness local | `/var/home/rubentxu/Proyectos/kotlin/Pipelinek-Test-Hardness/pipelinek-release-harness` | rama `certify/0.47.0-rc2`, HEAD `a97ad2b1` |
| Divergencia local vs remoto | `git rev-list --left-right --count origin/main...HEAD` | `0  74` (74 commits sin pushear) |
| Árbol sucio local | `git status --short` | 11 rutas: 4 modificadas, 7 sin seguimiento |

---

## 3. La capacidad existe, y está verde (OBSERVED)

```text
harness/check_run.py     2026-10-09 09:49:24   sin commitear
harness/handoff.py       2026-10-08 22:59:11   sin commitear
harness/assets.py        2026-10-09 09:44:33   sin commitear
tests/test_admission_check.py
tests/test_handoff_admission.py
tests/test_verdict_taxonomy.py
tests/test_channel_assets.py
```

```text
$ timeout 300 python -m pytest tests/test_admission_check.py tests/test_handoff_admission.py -q
53 passed in 0.43s
```

El módulo está diseñado contra la forma correcta del problema, y esto es lo que lo hace creíble:

- **El check se vincula a los bytes que juzga.** El payload ata source SHA, artifact SHA256, digest del veredicto del harness, perfil e identidad. Cuatro de las cinco condiciones de fallo cerrado de ADR-0105/PR-ADR-002 son de *binding*, no de lógica.
- **Una candidata supersedida nunca es `success`.** El comentario del módulo lo dice y es la razón: un check verde sobre bytes desplazados es exactamente lo que un mapeo ingenuo de veredicto a conclusión produciría.
- **La ausencia es fallo.** No hay ruta que publique `neutral` ni salte; una candidata sin veredicto, sin ZIP, o con uno ilegible publica `failure` con su razón.
- **La idempotencia se decide, no se asume.** Una conclusión de check run no se edita tras crearse; `plan_publication` compara contra lo ya publicado y devuelve `SKIP_IDENTICAL` o `CONTRADICTION` en vez de crear un segundo run con el mismo `external_id`.

Endpoints reales (`check_run.py`): `gh api --method POST --input -` para publicar, y lectura de `repos/{repo}/branches/{branch}/protection` para leer los contextos exigidos.

---

## 4. Lo que el harness sabe y nosotros no (OBSERVED)

`docs/roadmap/ROADMAP.md` del harness, línea 15:

```text
| H2 | Check Run vinculante de admisión |
    Positivo publica el check; negativo bloquea; main lo exige efectivamente |
    PARCIAL — código y tests listos; la protección de main sigue sin activar
    por decisión del operador |
```

y línea 71:

```text
Pendiente para cerrar el gate de H2: ejecutar una publicación real y activar
la protección en main — que es lo que hoy hace falso al gate, y que el
operador ha decidido no activar todavía.
```

El harness **sí** midió el mismo estado de `main` que medimos nosotros:

```text
$ gh api repos/Rubentxu/pipeline-kotlin/commits/a040c113.../check-runs --jq '.total_count'
0
$ gh api repos/Rubentxu/pipeline-kotlin/branches/main/protection --jq '.required_status_checks'
(null)
$ gh api repos/Rubentxu/pipeline-kotlin/contents/.github/workflows
404 Not Found
```

Los tres coinciden. La convergencia entre dos partes independientes sobre el mismo hecho es la señal más fuerte de este recibo: no dependemos de la palabra de nadie.

---

## 5. El hallazgo que B0 no anticipaba (OBSERVED)

El roadmap del harness afirma, línea 57:

```text
El roadmap B cita ADR-0105-admission-check-publication-authority.md como
autoridad de H2. Ese documento no existe upstream: el último ADR es
ADR-0104-s55-reactor-deferred.
```

Eso es **falso**, y la cronología muestra por qué:

| Hecho | Evidencia | Fecha |
|---|---|---|
| ADR-0105 creado | `git log -1 -- docs/v2/04-adrs/ADR-0105-…` → `acd12111` | 2026-10-08 09:12:45 |
| ADR-0105 en el remoto | `git merge-base --is-ancestor acd12111 origin/main` | sí |
| `check_run.py` escrito | `stat` | 2026-10-09 09:49:24 |
| Último fetch del harness de nuestro repo | `git log -1 --format='%ad' FETCH_HEAD` | **2026-09-29** |

El harness escribió su análisis **seis horas después** de que ADR-0105 saliera de `main`, sobre un árbol cuyo último contacto con nuestro repo es de hace diez días. Nunca hizo `fetch`; su `FETCH_HEAD` es de 2026-09-29.

```text
Causa raíz: el harness no sincroniza con upstream. Su autoridad documental
             se evalúa contra un snapshot de hace diez días, y en ese snapshot
             ADR-0105 genuinamente no existía.
```

La consecuencia no es cosmética. El harness declara su propia autoridad como
`docs/v2/08-production-readiness/adr-proposals/PR-ADR-002-admission-authority.md`,
en estado **PROPOSED**, y su AGENTS.md le advierte no atribuir aceptación a un
ADR propuesto. Si aceptamos ADR-0105 y el harness sigue leyendo PR-ADR-002, tenemos
**dos documentos que se declaran autoridad sobre el mismo check** y ninguna
política que resuelva el choque.

Dato adicional: `PR-ADR-002` está fechado 2026-09-26, doce días antes que ADR-0105,
y no menciona el número del ADR. No es que el harness lo contradiga; es que no lo ha visto.

---

## 6. Estado de G10 tras esta investigación (OBSERVED)

Las tres condiciones de G10, evaluadas juntas:

| Condición | Estado | Evidencia |
|---|---|---|
| Recibo inmutable escrito | **cumple localmente** | `docs/v2/07-uat/R1_RELEASE_0_48_0_RC1_RECEIPT.md` |
| Check de admisión publicado | **no cumple** | `check-runs total_count: 0` sobre `a040c113` |
| Protección de rama lo observa | **no cumple** | `required_status_checks: null`; el harness lo confirma por separado |

```text
G10      NOT_RUN
PRODUCT-GATE global   RP-5 PRODUCT_GATE_STOP
```

Sin cambios respecto a antes. La investigación no abrió una puerta: **el mismo veredicto, con una causa distinta y una remediación distinta.**

---

## 7. Qué es verdad y qué no (NOT_VERIFIED explícito)

- **NOT_VERIFIED**: que `check_run.py` funcione contra la API real de GitHub. Los 53 tests son unitarios con `gh_runner` inyectado; ninguno hace una publicación real. El propio roadmap del harness lo dice: falta "ejecutar una publicación real".
- **NOT_VERIFIED**: que main pueda recibir un check sin credenciales de escritura. Medimos `check-runs: 0` y `protection: null`, que es compatible con "nunca se intentó" y con "no se puede"; no distinguimos entre los dos.
- **NOT_VERIFIED**: qué haría falta para que el harness certificara `v0.48.0-rc1`. Su `evidence/` contiene hasta `v0.47.0`; no hay recibo para nuestra candidata, y su repo local dice `H1 BLOQUEADO` por no existir artefacto 0.48.x publicado **desde su punto de vista** — el nuestro sí está publicado desde `65928728`.
- **NOT_VERIFIED**: si `required_status_checks` se puede activar sin destructividad. Es un cambio de configuración del repo que el operador ha decidido no activar; no es nuestro para tomar.

---

## 8. Corrección a B0-F4

B0-F4 se mantiene **abierto**, pero su texto cambia:

```text
ANTES  la capacidad no existe todavía
AHORA la capacidad existe y está probada (53 tests), en 74 commits sin pushear
      más 4 módulos sin commitear, sobre un origin/main parado en 2026-09-29
```

Cambia lo que significa, porque cambia el dueño del bloqueo. "No existe" sugiere trabajo de implementación pendiente en un actor externo. "Existe, sin publicar, sin ejecutar, con la protección sin activar por decisión del operador" son tres acciones concretas, todas en el proyecto propietario, ninguna ejecutable desde este repositorio.

---

## 9. Cierre

B0 queda con el exit criterion de digest **completo** (utilidad única, migrados los sitios preservando bytes, fitness de frontera verde, 3683 tests sin fallos) y con la investigación del harness **cerrada y medida**. El work item no se cierra todavía: depende de la decisión del operador sobre publicar el árbol del harness y activar la protección de `main`, que son acciones externas con dueño externo.

Ninguna conclusión de este recibo requiere inventar un destino de Maven, unas credenciales o un veredicto de harness. Los tres siguen siendo `BLOCKED_EXTERNAL`, y ese estado es correcto, no un fallo pendiente de tapar.

---

Reference implementation consulted: harness `harness/check_run.py` + `harness/issues.py` (Rubentxu/pipelinek-release-harness @ a97ad2b1, local, sin pushear) y GitHub REST API v3 sobre `Rubentxu/pipeline-kotlin`.
Behaviour adopted: el shape del payload de admisión y las cinco condiciones fail-closed, como referencia de qué debería atar un check de admisión cuando exista. No adoptado: ninguna implementación; este repositorio no escribe checks ni reintroduce CI (ADR-0105 D3).
Intentional deviations: ninguno. Este repositorio no posee la capacidad ni tiene credenciales para ella.
Security implications reviewed: corollary de que G10 exige credenciales de escritura sobre `Rubentxu/pipeline-kotlin`; no se solicitó ninguna ni se creó ninguna.
Tests demonstrating the contract: los 53 tests del harness no forman parte de este repositorio; aquí la evidencia es la medición de API de este recibo (§4, §6).
