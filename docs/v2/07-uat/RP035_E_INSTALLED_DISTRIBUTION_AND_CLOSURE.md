# RP035-E — Cierre de WU-RP-035: distribución instalada, addendum y candidata

Estado: **EJECUTADO** — pendiente únicamente del veredicto del gate completo sobre el
árbol final, que se registra abajo al aterrizar.
Cycle SDDK: `p-1f3622e11c093341/rp-035-handler-continuation`
WorkItem: `ed95116d-a86b-4d3a-abcc-886ece9a209b`

## Qué cierra este slice

La parte de la promesa open-world que ningún test in-process podía demostrar: que el
producto **real** — el binario instalado — es extensible por un tercero con un Step
propiamente body-bearing.

## El hallazgo del slice: la capa que solo el binario instalado muestra

El run en la distribución instalada rechazó `example.repeat` con:

```text
Error: script uses non-canonical plugins; ...
  - Step 'repeat/registryblock-body-0' (example.repeat):
    block step 'example.repeat' is not owned by the canonical body engine
```

El gate de elegibilidad canónica (`analyzeCanonicalDurableExecution`) derivaba la
elegibilidad de bloque del registro **estático del core** filtrado a `CANONICAL_ENGINE`.
Un Step externo handler-driven compilaba, resolvía su política, pasaba todos los tests
in-process y aun así era rechazado en producción. El test de C llamaba al coordinator
directamente y esa capa no existe en su camino: es exactamente el hueco que el operador
anticipó al exigir "no usar solo `testImplementation(files(...jar))` como prueba final".

Corregido en `342897f0`: el gate resuelve el owner **declarado** desde el registro
efectivo (fallback al derivado estático) y admite `CANONICAL_ENGINE` y
`HANDLER_CONTINUATION` por igual. `LEGACY_LINEAR` y claves desconocidas siguen
rechazados; la ruta sin registro conserva el comportamiento exacto.

## Prueba en la distribución instalada

```text
pipelinek run --plugin-jar example-block-plugin-0.1.0.jar repeat-demo.pipeline.kts
script:   repeatBlock(2) { sh("echo iteration") }
resultado: EXIT 0 — Pipeline finished with SUCCESS
eventos:   2x EchoOutputCaptured (dos iteraciones), 3x StepStarted/StepFinished
           (1 padre repeat + 2 hijos sh), RunFinished success
```

La cadena completa, sobre bytes instalados y un JAR de tercero:

```text
plugin descubierto (--plugin-jar)
  -> script compilado contra la DSL del plugin (repeatBlock)
  -> elegibilidad canónica por owner declarado
  -> handler invocado
  -> BodyContinuation invocada 2 veces por el handler
  -> hijos ejecutados por el motor (journal por iteración)
  -> éxito
```

## Mapa completo de WU-RP-035

| Slice | Commit | Contenido |
| --- | --- | --- |
| A | `db23ad28` | RED discriminante (`expected 1, observed 0`) + caracterización de identidad durable |
| B | `83d5b2b0` | Owner `HANDLER_CONTINUATION`, `BodyContinuation`, capability, coherencia fail-closed, ADR-0081 enmendada |
| C | `5a2c8a7d` | Ruta handler-driven sobre la spine durable; `dispatchBody` intacto; cuerpo en la identidad del padre |
| D | `03e77df2` | `example.repeat` certificado sobre el JAR real por ServiceLoader (5/5) |
| E | `342897f0` | Elegibilidad desde el registro efectivo + run instalado verde |
| — | `25c670f7` | Addendum correctivo de `RP3_EXIT_REVIEW.md` |

## Registro del gate final

`./gradlew check` sobre el árbol de código `342897f0` (el delta posterior hasta HEAD es
solo documentación): **BUILD SUCCESSFUL in 18m 35s, 0 fallos**. Ese es el gate de cierre
de WU-RP-035.

## Tren de release

`v0.45.0` fue liberada por el operador apuntando a `a277d67a` (verificada en
`origin`: tag `refs/tags/v0.45.0` → `a277d67a`). El train 0.45.0 está cerrado; los
cambios de RP-035 salen en un tren nuevo **0.46.0**. Derivación desde `v0.45.0` hasta
HEAD: `feat(domain)` 83d5b2b0, `feat(engine)` 5a2c8a7d, `feat(examples)` 03e77df2,
`fix(engine)` 342897f0, `docs` 25c670f7 — sin breaking declarados, API puramente
aditiva (pipeline-domain.api +21/−0). Convención 0.x: bump de MINOR → **0.46.0,
candidate sequence 1**. Nada queda SUPERSEDED: la candidata c1 de 0.45.0 se convirtió
en la release.

## Candidata 0.45.0 sequence 2

Tras el gate verde: `:pipeline-release:candidateAdmission -Pcandidate.sequence=2` desde
HEAD, entrega de los bytes a `pipelinek-release-harness/inputs/dogfood/0.45.0/`
(sustituyendo c1, que queda SUPERSEDED por decisión del operador). CandidateId nuevo =
SHA-256 del ZIP canónico; mismo ReleaseTrain 0.45.0; ProductVersion sin cambio.
