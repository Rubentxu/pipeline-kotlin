# S2-D receipt — Directive plugin on the INSTALLED distribution (0.44.0 slice bytes)

- Fecha: 2026-09-30 · Ciclo SDDK: `p-1f3622e11c093341/train-s2-directive-plugin` (S2-D)
- HEAD de construcción: **`28dcd5c5a4da6364ca13b94aab94cb97676aa1fe`** (slice S2-D)
- Base del slice: `754ddda0bc3b7c35619a67f80f241691d78536d5` (v0.44.1)
- Diff digest (base..head): `c5d246a4d1d809583ee762daa97eeebf634c2a98622f563b6861669fe0582d3f`
- Árbol limpio al construir (`git status --porcelain` vacío).
- Por qué existe (RP-042): los recibos S1 certificaron **otros** bytes —
  `S1_EF_INSTALLED_DIRECTIVE_UAT_RECEIPT.md` (`8a5d240d…`, HEAD `1910083e`) y
  `S1_R0_G_INSTALLED_DIST_SMOKE_0_44_0_RC1.md` (`18ca1ab5…`, HEAD `2182575f`).
  Los bytes actuales incluyen S2-C y S2-D, luego la certificación **no se
  transfiere** y este recibo la re-emite contra los bytes del slice.

## Bytes del candidato

| Artefacto | SHA-256 | Tamaño |
|---|---|---|
| `pipelinek-0.44.0.zip` | **`a196153bf1a65320695335fc8ca22581c3a251cd7ca8ffce0e04caa9cfdda386`** | 92.317.348 bytes |
| `lib/pipeline-application-0.44.0.jar` (en `installDist` **y** dentro del ZIP — bytes idénticos) | **`c362ecd46594be54863ff9c3a5ebf256930b270c5a46893ad074b53229372c13`** | — |
| `examples/example-directive-plugin-0.1.0.jar` | `33ec2c3e9527bb725e2d4e8166656830787daccb3eb0b7afc91ee331638ccb11` | **sin rebuild** (build de `b6e1b28b`; verificado antes y después de las 5 ejecuciones) |

## Entorno

- Build: `cd v2 && ./gradlew :pipeline-application:installDist :pipeline-application:distZip`
  → `BUILD SUCCESSFUL in 6s`; 49 tareas (1 ejecutada, 48 up-to-date), exit 0.
- Fixtures en workspace temporal `/tmp/s2d-smoke/ws` (**no commiteados**, patrón S1-EF):
  - `directive.pipeline.kts` — `stage("locked")` con
    `directive("acme.lock", """{"resource":"prod-db"}""")` + `echo("body under lock ran")`.
  - `badargs.pipeline.kts` — idéntico con args `"""{not-json"""` (no-JSON).
  - Sintaxis de steps directos en stage (sin bloque `steps{}`), como
    `v2/compatibility/01-basic.pipeline.kts` y los recibos S1.
- Binarios: `v2/pipeline-application/build/install/pipelinek/bin/pipelinek` y el
  homónimo extraído del ZIP. Versión `0.44.0` (coincide con `v2/build.gradle.kts`).

## Escenarios (todos OBSERVADOS, comparables 1:1 con S1-E/F y S1-R0-G)

### E1 — `installDist`, con plugin, args válidos → GREEN

`exit=0` · stderr: `Discovered external directive plugins: example.lock.LockContributor` ·
eventos NDJSON (10): `CompilationStarted → CompilationFinished → RunStarted →
DirectiveAdmitted(seq 4, key=acme.lock, phase=BEFORE_STAGE, policy=evaluate) →
StageStarted → StepStarted → EchoOutputCaptured("body under lock ran\n") →
StepFinished → StageFinished(success) → RunFinished(success)`.
El body **SE EJECUTÓ** bajo la directiva admitida.
Digest stdout: `8451d7d78f6c4c89022cb32f7a6d9e6f2edbf016387d513b856612b00e3edc48`.

### E2 — `installDist`, sin plugin → DENIED fail-closed

`exit=1` · eventos: `CompilationStarted → CompilationFinished → RunStarted →
DirectiveDenied(seq 4) → RunFinished(failure)`.
`StageStarted`: **0** · `StepStarted`: **0** (el body nunca corrió).
`reason`: `unresolved directive 'acme.lock' in stage 'locked': no definition registered;
refusing to run the stage` (typed USER, sin stacktrace).
Digest stdout: `e86815051552fc48e01b3ded1c59153985cf99d21810c7aa43690ead5a821998`.

### EZ1 — desde el ZIP extraído, con plugin → GREEN

`exit=0` · `Discovered external directive plugins: example.lock.LockContributor` ·
cadena completa `… DirectiveAdmitted → StageStarted → StepStarted →
EchoOutputCaptured → StepFinished → StageFinished → RunFinished(success)`.
Digest stdout: `eb4972c9dc4330e187d22402234789a5c47df3211f1f7a4c1b86cb8c11f0580a`.

### EZ2 — desde el ZIP extraído, sin plugin → DENIED fail-closed

`exit=1` · `… DirectiveDenied(seq 4) → RunFinished(failure)`.
`StageStarted`: **0** · `StepStarted`: **0**.
Digest stdout: `0100bc05b61876824c11ae64a1802e03bda4914300921fb4c15c3f4d396e23aa`.

### E3-instalado (SHOULD) — args malformados CON plugin → **DENIED fail-closed (E3 CERRADO)**

`exit=1` · eventos: `CompilationStarted → CompilationFinished → RunStarted →
DirectiveAdmitted(seq 4, key=acme.lock, phase=BEFORE_STAGE, policy=evaluate) →
DirectiveDenied(seq 5) → RunFinished(failure)`.
`StageStarted`: **0** · `StepStarted`: **0** (el stage nunca arrancó).
`reason`:
`directive 'acme.lock' declared policy evaluate whose arguments could not be decoded:
acme.lock arguments are not valid LockInput JSON: Unexpected JSON token at offset 1:
Expected quotation mark '"', but had 'n' instead at path: $
JSON input: {not-json`
Digest stdout: `d61f4302ad392fff1e5a95f072359bd8a484432718960da19da04d2b1be43895`.

**Delta honesto frente a S1-E/F y S1-R0-G**: allí E3 fue
`exit=0` + `DirectiveAdmitted` + success ("garbage args → admitted"). Aquí es
`exit=1` + `Admitted → Denied` + cero efectos. Es exactamente el cierre del hueco
E3 (spec R2/D3) y se observa **sobre los bytes instalados**, no sólo en test.
El orden `Admitted → Denied` es ley (D4): la admisión por key fue correcta y se
observa; lo que falla es el contrato de decode de la directiva, no un veto.

## Re-pin de la caracterización E3 (R3)

Las TRES superficies del re-pin quedan declaradas y verificadas:

1. **Suite de contrato** — `DirectivePluginContractSuiteTest`, fila 8
   `with plugin - malformed evaluate args deny fail-closed before the stage starts`:
   `RunOutcome.Failure` + `FailureKind.USER` + reason que nombra `acme.lock` y el
   decode, `DirectiveAdmitted` antes de `DirectiveDenied`, y `StageStarted` ausente.
2. **Manifiesto de superficie** — fila `directives` de
   `docs/v2/surface/DSL_SURFACE_MANIFEST.md` describe el decode fail-closed de
   `Evaluate` y `ProvideContext` admitida-observada sin interpretación.
3. **Este recibo** — E3-instalado re-emitido con el delta observado arriba.

## `ProvideContext` (D5/D9) — estado declarado

`ProvideContext` permanece **admitida-observada SIN interpretación** (fail-closed
declarado, no por accidente). En esta fase el seam BEFORE_STAGE sólo decodifica
`Gate | Evaluate`; interpretar `ProvideContext` exigiría un seam de proyección de
contexto al body que ningún pedido de producto ha solicitado. Su fase natural es
DURING_STAGE.

## Contrato del candidato: estado

| Criterio (spec R1) | Resultado |
|---|---|
| Re-emisión sobre distribución instalada del slice | **OK** — `a196153b…`, HEAD `28dcd5c5` |
| E1/EZ1 GREEN con admission observable + body ejecutado | **OK** — `DirectiveAdmitted(acme.lock, BEFORE_STAGE, evaluate)` + `EchoOutputCaptured` |
| E2/EZ2 denied fail-closed sin plugin | **OK** — exit 1, typed USER, 0 `StageStarted`/`StepStarted` |
| `binary_sha256` registrado (ZIP + app-jar) | **OK** — `a196153b…` + `c362ecd4…` |
| Plugin JAR sin rebuild | **OK** — `33ec2c3e…` idéntico antes y después de las 5 ejecuciones |
| E3 cerrado sobre bytes instalados | **OK** — exit 1, `Admitted → Denied`, 0 efectos |
| Sin tag / release antes de UAT verde | **OK** — este recibo precede a cualquier tag; la decisión de release es del operador |

## Limitaciones honestas

- La autoridad de esta fila es el **gate local**. `:pipeline-application:detekt`
  FALLA sobre el HEAD del slice (`MaxLineLength` en
  `CanonicalDurableRunCoordinator.kt:480`, 162 chars > 160). Es un gate estático
  de estilo, no de ejecución: los binarios construidos aquí son funcionales y las
  5 ejecuciones de arriba son válidas, pero el ciclo **no** está verde hasta que
  ese lint se corrija y `check` vuelva a pasar. Queda registrado como finding
  bloqueante de la fase verify, no como nota.
- CI no aporta evidencia en esta ventana: la autoridad es el gate local + los
  smokes anteriores. No se simula estado de CI.
- Esto es UAT de **distribución instalada**, no la matriz completa de RP-042
  (Gradle/Maven/Node reales, rollback/restart, credenciales, corpus completo):
  esa sigue siendo deuda de RP-042 y no se afirma aquí.
- El harness externo `pipelinek-release-harness` sigue sin existir; la
  promoción a estable no se PROMUEVE desde este repo.
