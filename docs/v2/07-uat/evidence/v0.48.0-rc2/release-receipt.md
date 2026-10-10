# PipelineK v0.48.0-rc2 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde, tag inmutable
publicado en `origin`, material en `dist/candidates/v0.48.0-rc2/`, **GitHub Prerelease
publicada y asset verificado por re-descarga**. Pendiente: `CERTIFIED` por el
release harness tras una segunda construcción independiente y comparación
byte-a-byte.

> **Renovación de la candidata 2026-10-10** (auditoría `wu-rp-053-workspace-contract`):
> la primera construcción de `v0.48.0-rc2` (SHA-256 `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`,
> 88.2 MB) llevaba `Implementation-Version: 0.48.0-rc2` (4-componente SemVer) en
> el manifest, lo que el `RuntimeApiVersion` rechazaba con `FATAL` en preflight.
> La candidata renovada (SHA-256 `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6`,
> 88.2 MB) lleva `Implementation-Version: 0.48.0` (3-componente) y la release
> `v0.48.0-rc2` mantiene su identidad y tag. Detalle en la sección
> **"Auditoría wu-rp-053-workspace-contract y renovación de la candidata"** al
> final de este recibo.

**Release:** prerelease en GitHub. Asset re-descargado y verificado: sha256 y bytes
idénticos a los medidos antes de publicar.

- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc2
- **Tag:** `v0.48.0-rc2` (anotado, peel = `74c5331` = `origin/main` = HEAD).
- **Commit de build:** `74c5331` (version bump 0.48.0 → 0.48.0-rc2; regla 9 de AGENTS.md:
  el tag se crea sobre commit ya integrado).
- **Push:** HECHO. `git push origin main` llevó `543e1cc5..e518bc82`. `git push origin
  v0.48.0-rc2` llevó el tag.

**Candidata:** `0.48.0-rc2`
**Tag:** `v0.48.0-rc2` (anotado, peel = `74c5331` = HEAD = `main` local).
**Commit de build:** `74c5331` (version bump 0.48.0 → 0.48.0-rc2; regla 9 de AGENTS.md:
el tag se crea sobre commit ya integrado).
**Rama:** `main`
**Candidata anterior:** `v0.47.0-rc3` (`4c372f2`)
**Integración en `main`:** HECHA. Las 4 commits de cierre viven en `main` local
(`0d2ee1a` B1 doc, `98e25f3` WIP-11 receipt verde, `b3e60c9` F-1 fix, `07ecd02` F-2/F-3 fix);
`3f59c57` es la addenda al WIP-11 receipt con la causa real de F-1.

Esta es una **release candidate**, no una release estable. El repositorio entrega el
material para `pipelinek-release-harness`. La certificación externa y la promoción
estable pertenecen al harness y deben reutilizar estos mismos bytes, sin reconstrucción.

## Train derivada del historial

| Señal | Valor |
|---|---|
| Commits en `v0.47.0-rc3..v0.48.0-rc2` | 312 |
| `feat` | 42 |
| `fix` | 60 |
| `refactor` | 18 |
| `docs` | 126 |
| `test` | 46 |
| `chore` | 10 |
| `style` / `perf` | 3 + 3 |
| `release` / merge | 2 + 2 |
| Train | **MINOR** → `0.48.0` (release estable cuando el harness certifique) |

## Material inmutable

Directorio local ignorado por Git (`.gitignore:50` → `/dist/`):

```text
dist/candidates/v0.48.0-rc2/
├── pipelinek-0.48.0-rc2.zip
├── pipelinek-0.48.0-rc2.sbom.json
└── SHA256SUMS
```

| Material | Bytes | SHA-256 |
|---|---:|---|
| `pipelinek-0.48.0-rc2.zip` (renovado, `Implementation-Version: 0.48.0`) | 88 200 000 | `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6` |
| `pipelinek-0.48.0-rc2.zip` (anterior, `0.48.0-rc2` 4-comp, **retirado**) | 88 200 000 | `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7` |
| `pipelinek-0.48.0-rc2.sbom.json` (renovado) | 1 300 000 | `057beae6d25bbcacebe67c3952b2667c9e62de4f9528a78fbafa56481310e5e1` |

Comprobaciones sobre los bytes definitivos:
- `unzip -t` del ZIP: OK
- `Implementation-Version` en los jars de pipelinek (`api`, `files`, `http`, `runtime`,
  `scm-git`, `utilities`, `junit`): `0.48.0` ✓ (3-componente SemVer, pre-release vive
  solo en tag y nombre del ZIP)
- Determinismo del build: build único (no se ha repetido para doble-verificación todavía).

## Gate de distribución instalada (S0.5)

Este es el gate que cierra el bloque B1 y que la regla "gate local completo" del roadmap
exige antes de declarar CANDIDATE_PUBLISHED. Log completo: `/tmp/wip11-full-gate-v2.log`.

| Comprobación | Resultado observado |
|---|---|
| `./gradlew check --rerun-tasks --console=plain --no-daemon` | `BUILD SUCCESSFUL in 31m 3s` |
| Tareas ejecutadas | 325 |
| Tests (global, todos los módulos) | 5611 |
| Failures | 0 |
| Errors | 0 |
| Skipped | 144 (incluye 2 `@Disabled` documentados en WIP-4/5) |
| `pipeline-application` (módulo principal) | 2811 tests, 0 failed, 0 errors, 123 skipped |

El primer gate sobre `2f5ba9aa` (WIP-11 original) terminó con 3 fallos reales
(F-1, F-2, F-3). El addendum al WIP-11 receipt (`3f59c57` + `WIP11_GATE_FINAL_STATUS.md`)
documenta el cierre: F-1 fue un stream-id drift pre/post OBS-C2.3 en `B1aSh > b`
(test-only, no regresión del producto); F-2 y F-3 fueron entradas legítimas en los
ledgers S6-PRE / S6-PRE 2 con la razón de su permanencia.

## Cierres del bloque B1

- **F-1**: `b3e60c9 fix(test): B1aSh durable arm aligned to OBS-C2.3 stream id shape`
- **F-2 + F-3**: `07ecd02 fix(test): S6-PRE + S6-PRE 2 fitness ledgers`
- **WIP-11 receipt amend**: `3f59c57 docs(uat): WIP-11 receipt amend — real F-1 cause`
- **WIP-11 receipt verde**: `98e25f3 docs(uat): WIP-11 — gate verde, CANDIDATE_PUBLISHED`
- **B1 feature doc**: `0d2ee1a docs(odd): B1 feature doc → CANDIDATE_PUBLISHED`
- **Version bump**: `74c5331 release: bump product version to 0.48.0-rc2`

## Deuda residual priorizada (siguiente ciclo)

| ID | Tipo | Severidad |
|---|---|---|
| OUT-01 | `SegmentOutputStore` prune filter permisivo (test `@Disabled`) | HIGH |
| OUT-02 | `SegmentOutputStore` `safeStreamName` no inyectivo (test `@Disabled`) | HIGH |

Ambos requieren política de migración de formato del Output Plane antes de poder
retirar el `@Disabled`. Mientras tanto, los 2 tests adversariales no bloquean la
release (están documentados en WIP-4/5 con la condición de habilitación).

## Pendiente para este bloque (WIP-11 cierre / WIP-12)

- **WIP-11**: ✅ push a `origin/main` HECHO, ✅ publicación del Prerelease HECHA,
  ✅ verificación de asset re-descargado HECHA (sha256 idéntico al material stage).
- **WIP-12**: ✅ ROADMAP refrescado en `4c725e4`, ✅ handoff al release harness en
  `4c725e4` (`docs/v2/07-uat/evidence/v0.48.0-rc2/handoff-to-release-harness.md`).
- **CERTIFIED**: pendiente; `pipelinek-release-harness` lo emite tras la segunda
  construcción independiente y la comparación byte-a-byte (mismo patrón que
  v0.42.0-rc1 y v0.47.0-rc3). El defecto del primer release (manifest con
  `-rc2`) ya está corregido en el material renovado; el certifier debería
  pasar la próxima vez que consuma este SHA.

## Auditoría `wu-rp-053-workspace-contract` y renovación de la candidata

**Fecha:** 2026-10-10
**Trigger:** certifier local en
`~/.local/share/pipelinek-release-harness/candidates/0fd6aec2ddf8f7a1/certification-result.json`
devolvió `CERTIFICATION_FAILED` con 7 artefactos `out/wu-rp-053-*.txt` faltantes
y `pipeline_exit: 3, pipeline_outcome: null` sobre la imagen
`localhost/pipelinek-harness:dogfood-0.48.0-rc2`.

### 1. Estado del certifier (medido, no inferido)

Recibido por el certifier local (`~/.local/share/pipelinek-release-harness`):
- `candidates/0fd6aec2ddf8f7a1/received_at`: `2026-10-10T14:29:50+00:00`
- `candidates/0fd6aec2ddf8f7a1/sha256`: `0fd6aec2ddf8f7a1ff95bbb0cf64961b384ee078f1846acd94844925ea4ffea7`
  (igual al publicado)
- `candidates/0fd6aec2ddf8f7a1/certification-result.json`: `decision: CERTIFICATION_FAILED`,
  `created_at: 2026-10-10T14:52:38+00:00` (corrida 22m 48s tras la recepción),
  firmado con `signature: sha256:c2ee7f45...`.

### 2. Diagnóstico

`runs/e2bb72560af54bf794545d8e0a5189e5/logs/pipelinek.log`:
```
FATAL — version '0.48.0-rc2' read from
/dev/rubentxu/pipeline/v2/application/pipelinek-version.properties
is not MAJOR.MINOR.PATCH. The SDK admits plugin apiRanges against
a three-component SemVer and nothing else, so a version it cannot
read is a version it cannot safely compare.
```

`runs/e2bb72560af54bf794545d8e0a5189e5/scenario/pipeline.kts`: 4 stages
(`dir-nested-3`, `read-cross-stage`, `parallel-branches`, `dir-reattach`) que
escriben 7 marcadores en `/workspace/out/wu-rp-053-*.txt`; **ninguna stage
alcanzó a ejecutarse** porque el preflight abortó con `FATAL` antes de la
primera.

`runtime/scenario/pipeline.kts` espera 5 marcadores (`cwd-a-b-c`, `lines`,
`readback`, `branch-left`, `branch-right`, `cwd-x-y`) — el certifier añadió
`lines` y `cwd-a-b-c` a la lista; los 7 nombres son los correctos.

### 3. Causa raíz (una sola, demostrada)

El bump de versión `v2/build.gradle.kts:87` cambió `version = "0.48.0"` por
`version = "0.48.0-rc2"`. `generateVersionResource` lo propagó verbatim al
`pipelinek-version.properties` y al `Implementation-Version` del jar manifest.
El runtime (`RuntimeApiVersion.parse`) exige exactamente MAJOR.MINOR.PATCH y
falla-closed en preflight.

**Una sola línea de código mal** ⇒ un único `FATAL` en preflight ⇒ el escenario
no llegó a la primera stage ⇒ 7 marcadores faltantes.

Los 6 "errores independientes" en `certification-result.json` son 1 (preflight
aborta) + 6 (artefactos esperados no producidos porque nunca se intentó
escribirlos). No hay seis causas distintas.

El contrato de release model v2 (v0.42.0-rc1, fd06be7c) y el test RED
explícito `RuntimeApiVersionPrereleaseProbeTest` (KDoc: "the version is the
final 0.48.0"; "P3: 0.48.0-rc1 is illegal BY DESIGN") ya documentaban que la
versión del producto es SIEMPRE 3-componente; el sufijo `-rcN` vive en el tag
y el nombre del ZIP, no en el manifest. El bump los puso en el lugar
equivocado.

### 4. Aislamiento y falsación (mínima antes de modificar código)

Reproducción contra la distribución publicada original (88.2 MB,
SHA-256 `0fd6aec2...`):
```
$ unzip -p pipelinek-0.48.0-rc2.zip 'pipelinek-0.48.0/lib/pipeline-application-0.48.0-rc2.jar' \
  | strings | grep -E 'Implementation-Version|version=0'
Implementation-Version: 0.48.0-rc2
version=0.48.0-rc2
```

El binario **lleva el sufijo `-rc2` en su manifest**. La causa raíz es
exactamente esa. No es defecto del escenario, no es defecto del harness, no
es defecto del entorno: es defecto del **runtime publicado**.

### 5. Fix aplicado (sin relajar comprobaciones de integridad/publicación)

1. **Revertido a 3-componente SemVer:** `v2/build.gradle.kts:87` `version =
   "0.48.0"`. Mismo tag `v0.48.0-rc2`, mismo ZIP `pipelinek-0.48.0-rc2.zip`,
   contenido del binario limpio.
2. **Regresión añadida:** `RuntimeVersionFormatGuardTest` en
   `v2/pipeline-application/src/test/kotlin/.../application/` con dos aserciones
   activas (no `@Disabled`): la versión es 3-componente y no contiene
   pre-release separator. Este test habría FALLADO con `0.48.0-rc2` y
   ahora es verde con `0.48.0`. Es la guardia positiva que sustituye al
   probe RED `@Disabled`.
3. **Reconstruido** distZip + sbom con el `version=0.48.0` interno. JAR
   verificado: `Implementation-Version: 0.48.0`,
   `pipelinek-version.properties: version=0.48.0`.
4. **Re-publicada** la Prerelease en GitHub (mismo tag, mismo título,
   mismo body) con el nuevo asset. SHA-256
   `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6`.
5. **Re-verificado** por re-descarga: `curl -sL https://github.com/Rubentxu/pipeline-kotlin/releases/download/v0.48.0-rc2/pipelinek-0.48.0-rc2.zip`
   → `sha256sum` → `4bec0844...` (idéntico al material stage).
6. **No declarada** `v0.48.0-rc2` `CERTIFIED`; el certifier (release harness
   o pair integrador) debe re-correr contra el nuevo asset y producir
   `PAIR_CERTIFIED` antes de cualquier promoción.
7. **Resultado fallido original conservado** en
   `~/.local/share/pipelinek-release-harness/candidates/0fd6aec2ddf8f7a1/certification-result.json`;
   la SHA `0fd6aec2...` queda retirada pero registrada en la tabla de arriba
   para trazabilidad.

## Notas operativas

- `git fetch origin --tags --prune` debe pasar antes del push para evitar el
  conflicto de tag que WIP-1 documenta en `V0470_RC1_ANNOTATED_TAG_PRESERVATION.md`.
- AGENTS.md "release candidates": el repositorio NO promociona a estable. La promoción
  la hace `pipelinek-release-harness` con los mismos bytes, sin reconstrucción.
