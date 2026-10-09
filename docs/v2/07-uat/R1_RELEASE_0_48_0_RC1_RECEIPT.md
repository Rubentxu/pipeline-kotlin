# R1 — Release 0.48.0-rc1: plataforma S6 integrada

```text
release_tag:        v0.48.0-rc1
release_kind:       PRERELEASE
source_sha:         6e8e86bd2e234dcaa4fcb4061ebc6e9fbeb7ce0b
main_sha:           6e8e86bd2e234dcaa4fcb4061ebc6e9fbeb7ce0b
tree_sha:           5e970e30961c5c4376c956b3285f321c90716b9f
candidate_id:       sha256:a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93
zip_sha256:         a4620df4855895e3cc14d5d8a05ee7bd64a75d128d3184be659defe0b009fb93
sbom_sha256:        057beae6d25bbcacebe67c3952b2667c9e62de4f9528a78fbafa56481310e5e1
manifest_sha256:    470dee6df5cf64accf57cd4fab261d948058106c551f1499378bcce2c8692932
git_integration:    PASS
local_gate:         PASS
installed_uat:      PASS
harness_verdict:    BLOCKED_EXTERNAL
maven_channel:      NOT_APPLICABLE
tests:              4716
failures:           0
errors:             0
skipped:            134
mutations:          1 (pin de bytes del plugin directive)
known_limitations:  ver sección 7
sddk_closeout:      SATISFIED
remote_release_url: https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc1
```

`harness_verdict: BLOCKED_EXTERNAL` no es una falta de este bloque: `RUNTIME_VERSION` y
`MANIFEST_VERSION` no son derivables del ZIP y los aporta el harness. Sin ese verdict,
`v0.48.0` **no** puede declararse estable.

---

## 1. UAT de aceptación

| UAT | Estado | Cómo se midió |
|---|---|---|
| R1-UAT-01 v0.47.0 en la historia de main | **PASS** | `merge-base --is-ancestor 3ec99a4c main` |
| R1-UAT-02 commit de rc1 alcanzable desde main | **PASS** | `main == 6e8e86bd == tag` |
| R1-UAT-03 SHA256 del ZIP = CandidateId | **PASS** | ver §3 |
| R1-UAT-04 las cuatro familias ejecutan | **PASS** | ver §4 |
| R1-UAT-05 plugin no admitido, cero efectos | **PASS** | ver §5 |
| R1-UAT-06 gate verde en el SHA integrado | **PASS** | ver §2 |
| R1-UAT-07 assets verificables | **PASS** | ver §6 |
| R1-UAT-08 harness declarado BLOCKED_EXTERNAL | **PASS** | ver §7 |

---

## 2. R1.UAT-06 — gate sobre el SHA integrado

Gate forzado, ejecutado **desde `main` ya integrado**, presupuesto derivado de los 1627 s
medidos en W6 más un 25 % de margen explícito (2034 s):

```text
BUILD SUCCESSFUL in 28m 26s
325 actionable tasks: 325 executed      ← ninguna up-to-date
```

Resultado run-scoped (solo XML de esta invocación): **4716 tests, 0 fallos, 0 errores,
134 skips, 756 clases, 18 módulos**.

### Dos falsos verdes descartados en este bloque

1. **La primera admisión fue `UP-TO-DATE`.** `candidateAdmission` devolvió exit 0 en 2 s y los
   artefactos seguían fechados el 30 de septiembre; el ZIP era de las 12:52, **anterior al merge**.
   Una admisión sobre bytes de otro SHA es exactamente lo que R1 prohíbe. Se relanzó con
   `--rerun-tasks` y se verificó la marca de tiempo del ZIP reconstruido.
2. **El veredicto se leyó del log, no por exit code, en un primer intento.** Un `awk` que buscaba
   la primera línea `BUILD` posterior al recuento capturó `BUILD SUCCESSFUL in 1s` de un build
   anidado de una tarea `Exec`. El veredicto real es la **última** línea `BUILD` del log (1548),
   la que sigue al recuento `325 actionable tasks`.

---

## 3. R1.UAT-03 — identidad del candidato

Seis cruces, todos verificados sobre los bytes **descargados del remoto**, no sobre la copia local:

```text
PASS  candidate_id == SHA256 real del ZIP
PASS  manifest.asset.sha256 == ZIP real
PASS  handoff.artifact.sha256 == ZIP real
PASS  handoff.sbom.sha256 == SBOM real
PASS  handoff.distribution_manifest.sha256 == manifiesto real
PASS  manifest.sha256sums == SHA256SUMS real
```

```text
product_version    0.48.0        (exact token, sin sufijo -rc)
archive_root       pipelinek-0.48.0
implementation_ver 0.48.0
source_commit      6e8e86bd2e234dcaa4fcb4061ebc6e9fbeb7ce0b
runtime            pipeline 0.48.0   (CLI instalada, ejecutada)
```

`rootProject.version` sigue siendo la autoridad única (`v2/build.gradle.kts:87`); la condición de
candidata vive solo en el tag y el handoff, según `release_train=0.48.0`,
`candidate_sequence=1`.

---

## 4. R1.UAT-04 — las cuatro familias, sobre los bytes remotos

Ejecutadas contra el binario **extraído del ZIP descargado**, no contra un `installDist` local.

| Familia | Testigo observado | Resultado |
|---|---|---|
| Step atómico + Event + capability | `PluginEventEmitted` `example.uppercase.applied`, `emittedBy: example.uppercase` | exit 0 |
| Block Step | `registryblock-body-0/sh-0` ejecutado **3 veces** exactas | exit 0 |
| Directive | `DirectiveAdmitted` × 1 | exit 0 |
| Event contribution | mismo evento del plugin, emitido por el handler que corrió | exit 0 |

El Block se cuenta por **nombre de step completo**, no por subcadena: `StepStarted` y
`StepFinished` llevan ambos `sh-0`, así que un conteo ingenuo habría dado 6.

---

## 5. R1.UAT-05 — las negativas producen cero efectos

| Escenario | Mecanismo | exit | `StageStarted` | `StepStarted` |
|---|---|---|---|---|
| Directive **sin** JAR | admisión | 1 | **0** | **0** |
| Block **sin** JAR | compilación | 1 | **0** | **0** |

La negativa del Directive lo dice el propio producto:
`unresolved directive 'acme.lock' in stage 'locked': no definition registered; refusing to run the stage`.

Las dos fallan por **mecanismos distintos a propósito**: el Block no resuelve el símbolo al
compilar, el Directive compila y muere en admisión. Fusionarlas en una sola afirmación habría
ocultado cuál mecanismo se ejercitó.

---

## 6. R1.UAT-07 — assets publicados

Cinco assets en el release. Verificados descargándolos de nuevo en un directorio limpio:

```text
pipelinek-0.48.0.zip             a4620df4…
pipelinek-0.48.0.sbom.json       057beae6…
distribution-manifest.json       470dee6d…
SHA256SUMS                       (cubre ZIP y SBOM)
candidate-handoff.json           3682f0b3…
```

### Un defecto real encontrado y corregido en los assets

La primera publicación llevaba un `SHA256SUMS` **incoherente**, y la verificación remota lo
detectó: `distribution-manifest.json: La suma no coincide`.

La causa era una **dependencia circular** entre dos ficheros: el manifiesto declaraba la suma de
`SHA256SUMS`, y `SHA256SUMS` incluía la suma del manifiesto. Cada uno había sido medido antes de
la edición final del otro, así que ambos quedaban obsoletos en el momento de publicarse.

Se rompió el ciclo en una sola dirección: `SHA256SUMS` cubre **ZIP y SBOM**, y el manifiesto
declara la suma de `SHA256SUMS`. Los tres assets se re-publicaron y la verificación remota
repetida da `PASS` en los seis cruces de §3.

---

## 7. La corrección que esta candidata entrega

El pin de bytes del plugin directive **era inestable**. Dos construcciones limpias del mismo
checkout, sin un solo cambio de código, dieron:

```text
c424a9b2…   (primera construcción)
74f8eebd…   (segunda construcción)
```

Los tres plugins de ejemplo no declaraban `isPreserveFileTimestamps=false` ni
`isReproducibleFileOrder=true`, así que el archivo arrastraba los mtimes de las fuentes y
ordenaba las entradas por iteración del sistema de ficheros.

Aplicadas a los tres, los digests son estables en dos construcciones limpias independientes:

| Plugin | Digest medido |
|---|---|
| directive | `283f89d7aae74580d0f57430d8f6ed7a36f0b9428ed48ee515b49136a78c34a8` |
| block | `64049d2484ac4cca596821d496a97b6343585981a70342b88a7101b6686538dc` |
| uppercase | `29050545ffd1bbd050c83a0da91df21b3fcad2b5fd469227460995b8f7efab5d` |

**El valor medido difiere del que traía la rama integrada** (`060f6ef5…`): S6 escribe un
documento de procedencia dentro del JAR, así que el artefacto es legítimamente distinto.
Copiar la constante de la rama habría sido incorrecto; por eso el pin se midió después del merge.

### Mutación

Poner el pin a ceros mata la fila exacta:

```text
DirectivePluginContractSuiteTest >
  plugin jar is the certified build and was not rebuilt for this core() FAILED
exit=1
```

Restaurado y verificado por hash (`a9b875c701c842f2`), verde de nuevo.

---

## 8. Lo que este recibo NO certifica

- **Certificación externa y promoción estable.** R3 sigue abierto. El harness debe emitir el
  verdict y el check G10; esta prerelease no puede promoverse sin ello.
- **Canal Maven remoto.** No hay bloque `publishing {}` remoto, ni firma, ni credenciales
  OSSRH en el repositorio. R2 permanece `BLOCKED_EXTERNAL`. Anunciar Maven usando `sdk-repo` o
  `mavenLocal()` está prohibido explícitamente y no se ha hecho.
- **`SHA256SUMS` no cubre el manifiesto ni el handoff**, por diseño del des-ciclo de §6. La
  cobertura de esos dos es el campo `sha256` del handoff y el `sha256sums` del manifiesto.
- **El sidecar `.pinned` (376 líneas) no fue auditado línea a línea**; solo se comprobó el digest
  medido de forma independiente.
- **`build/sdk-repo` sigue acumulando versiones previas** (`0.42.0-rc1` … `0.47.0` junto a
  `0.48.0`). Inocuo para la verificación local del SDK, relevante para el requisito de
  coordenadas inmutables de W8/R2.
- **PRODUCT-GATE sigue `BLOCKED_EXTERNAL`**: no hay superficie de CI desde `754ddda0`. "CI verde"
  no es una evidencia disponible en este repositorio.

---

## 9. Cadena de integración

```text
s6-plugin-sdk  ed32a579  (W6 cerrado)
      │
      ├─ merge fix/reproducible-directive-plugin-jar (3ec99a4c, tag v0.47.0)
      │     → corrige reproducibilidad + hace alcanzable v0.47.0 desde main
      │
      └─ 6e8e86bd  ──fast-forward──▶ main   (105 commits, 0 perdidos)
                                        │
                    gate forzado 28m26s · 4716 tests · 0/0
                                        │
                    ZIP + SBOM + manifiesto + handoff + SHA256SUMS
                                        │
                    candidateAdmission (sequence 1, train 0.48.0)
                                        │
                    tag v0.48.0-rc1 → 6e8e86bd
                                        │
                    GitHub Prerelease + assets descargados y verificados
```

Ningún commit se perdió: `main` no tenía commits exclusivos y la integración fue
fast-forward puro. El tag `v0.47.0` **no se movió**: sigue en `3ec99a4c`, ahora alcanzable.
