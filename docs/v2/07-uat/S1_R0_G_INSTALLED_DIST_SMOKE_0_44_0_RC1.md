# S1-R0-G receipt — installed-distribution smoke for candidate `0.44.0-rc1`

- Fecha: 2026-09-29 · Ciclo SDDK: `p-733fb505b5a6bd2d/s1-r0-concurrency-characterisation`
- HEAD de construcción: `2182575f` (bump de versión) sobre `36ca9941`
- Fuente: `v2/pipeline-application:installDist` y `:distZip` sobre HEAD; árbol limpio
  salvo `BACKLOG.md` untracked pre-existente.

## Por qué este recibo existe (RP-042)

El recibo S1-E/F (`S1_EF_INSTALLED_DIRECTIVE_UAT_RECEIPT.md`) certificó los **bytes de
`0.43.0-rc1`**, construidos en `1910083e`. El commit `0fa47f74` de este mismo ciclo cambió
código de producción (`Main.kt`). WU-RP-042 (roadmap §6) es normativa en este punto:

> Publish only the EXACT ZIP tested. Freeze API/schema for the candidate; certification
> expires if the bytes change.

Por tanto la certificación anterior **no se transfiere** a `0.44.0-rc1`, y este recibo la
re-emite **contra los bytes nuevos**, antes de que exista tag.

## Bytes del candidato

| Artefacto | SHA-256 | Tamaño |
|---|---|---|
| `pipelinek-0.44.0-rc1.zip` | **`18ca1ab57546614fb8ffa3669882e52878d1030029f9d634d5f76abc19cd0136`** | 92.174.000 bytes |
| `lib/pipeline-application-0.44.0-rc1.jar` (dentro del zip) | **`502c6306fc6113a348764bcc45691569e577645ee3f3f10a7aa034371136d6d3`** | — |
| `examples/example-directive-plugin-0.1.0.jar` | `33ec2c3e9527bb725e2d4e8166656830787daccb3eb0b7afc91ee331638ccb11` | (sin cambios respecto a S1-D) |

Ambos binarios (el de `installDist` y el extraído del ZIP) reportan `pipeline 0.44.0-rc1`,
coincidente con la autoridad única de versión (`v2/build.gradle.kts`).

## Escenarios (todos OBSERVED, comparables 1:1 con S1-E/F)

### E1 — `installDist`, con plugin → GREEN

`exit=0` · stderr: `Discovered external directive plugins: example.lock.LockContributor` ·
eventos: `CompilationStarted → CompilationFinished → RunStarted → DirectiveAdmitted
(seq 4, key=acme.lock, phase=BEFORE_STAGE, policy=evaluate) → StageStarted → StepStarted →
EchoOutputCaptured → StepFinished → StageFinished → RunFinished`.

### E2 — `installDist`, sin plugin → DENIED fail-closed

`exit=1` · eventos: `CompilationStarted → CompilationFinished → RunStarted →
DirectiveDenied (seq 4) → RunFinished`.
`StageStarted`/`StepStarted`: **0** (el body nunca corrió).
`reason`: `unresolved directive 'acme.lock' in stage 'locked': no definition registered;
refusing to run the stage` (typed, sin stacktrace).

### EZ1 — desde el ZIP extraído, con plugin → GREEN

`exit=0` · `DirectiveAdmitted` y la cadena completa de efectos hasta `RunFinished`.

### EZ2 — desde el ZIP extraído, sin plugin → DENIED fail-closed

`exit=1` · `DirectiveDenied` y `RunFinished`; `StageStarted`/`StepStarted`: **0**.

### E3 — argumentos malformados (`{not-json`) → Admitted (hallazgo de alcance, ya conocido)

`exit=0` con `DirectiveAdmitted`. **Idéntico a S1-E/F**, sin variación: la admisión es
registry-key match (`StageDirectivePlanner.decide` no llama a decode) y el decode pertenece a
la fase de interpretación, no implementada en S1 por diseño. No es regresión de este candidato;
queda como entrada del diseño de interpretación.

## Fix del CLI verificado EN EL BINARIO DISTRIBUIDO

Este ciclo entregó un fix de fail-closed en la frontera CLI (`0fa47f74`). Se verificó sobre el
binario instalado, no sólo sobre el código:

```text
$ pipelinek validate /nonexistent/missing.pipeline.kts
Error: pipeline script not found or not readable: /nonexistent/missing.pipeline.kts
EXIT=2
líneas con "Exception in thread" / "at dev.rubentxu" / "at java.": 0
```

Contrato canónico (`Main.kt`, WU-LPR-011 F3): `0` éxito · `1` fallo del pipeline ·
`2` invocación/compilación. Sin stacktrace.

## Contrato del candidato: estado

| Criterio | Resultado |
|---|---|
| distZip reproducible desde commit inmutable | OK — `18ca1ab5…`, sin cambios de árbol |
| Versión del binario == versión del candidato | OK — `0.44.0-rc1` en `installDist` y en el ZIP |
| Installed-dist smoke con plugin JAR | E1/EZ1 GREEN con discovery observable |
| Par de aislamiento fail-closed | E2/EZ2: denied typed, 0 efectos |
| Fix CLI presente en el artefacto | OK — exit 2, typed, sin stacktrace |
| Sin tag antes de UAT verde | OK — este recibo precede al tag |
| UAT de la batería externa | **NOT_RUN** — el harness `pipelinek-release-harness` no existe todavía; la autoridad de certificación estable sigue siendo suya |

## Limitaciones honestas

- La autoridad de esta fila es el **gate local** (L5). CI self-hosted está offline en esta
  ventana (runs `queued` >34 min y `cancelled` a la hora), por lo que GitHub Actions no aporta
  evidencia: se registra como tal y no se simula.
- Esta es UAT de **distribución instalada**. No es la matriz completa de RP-042 (Gradle/Maven/Node
  reales, rollback/restart, credenciales, corpus completo): esa sigue siendo deuda de RP-042 y no
  se afirma aquí.
- El harness externo sigue sin existir; la promoción a estable no se PROMUEVE desde este repo.
