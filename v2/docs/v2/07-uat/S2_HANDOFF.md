# Handoff S2 — Slice 2 (YAML + findFiles + zip + unzip)

Pipeline-k v2 local-first plugin ecosystem — Slice 2 cerrado y CERTIFICADO.
Fecha de cierre: 2026-09-19. Sesión cerrada para handoff; próximo día laboral retoma en S2-corpus L4 + slice 3.

## Estado del workspace

- **HEAD** en `main`: `215f8cfd`
- **Working tree**: limpio (sólo hay docs huérfanos no relacionados: `docs/pipeline-kotlin-local-production-ready-2026-09-18/`)
- **SDKMAN**: sigue `WAITING_EXTERNAL` (no instalado; irrelevante para S2)
- **`fixture05ScriptedIf`**: pre-existing regression confirmada, no introducida por S2
- **Tests S2**:
  - L0 compile: verde
  - L1 (corpus S2 targeted, 6/6): verde
  - L2 (`:pipeline-step-sdk:utilities:test`): 112 contract + 6 safety = **118/118** verde
  - L4 (corpus completo 01–29 + discoverable): pendiente de correr mañana

## Steps CERTIFICADOS en Slice 2 (5)

| Step | Jenkins reference | Cert commit | Receipt commit | State |
| --- | --- | --- | --- | --- |
| `core-utils.readYaml` | pipeline-utility-steps-plugin::ReadYamlStep | `2a7beedb` | (en bloque S2.1) | **CERTIFIED** |
| `core-utils.writeYaml` | pipeline-utility-steps-plugin::WriteYamlStep | `aa5cf444` | `fa8f9633` | **CERTIFIED** |
| `core-utils.findFiles` | pipeline-utility-steps-plugin::FindFilesStep | `ed0c43a0` | `df534f36` | **CERTIFIED** |
| `core-utils.zip` | pipeline-utility-steps-plugin::ZipStep + CompressStepExecution | `28968af7` | `ec9f7be1` | **CERTIFIED** |
| `core-utils.unzip` | pipeline-utility-steps-plugin::UnZipStep + UnZipStepExecution | `8af34b81` | `ed1c262d` | **CERTIFIED** |

## Counters

| Metric | Antes S2 | Después S2 |
| --- | --- | --- |
| Certified utility Steps | 3 (readJson, writeJson, sha256) | **8** (+5: readYaml, writeYaml, findFiles, zip, unzip) |
| Utility Steps en `LEGACY_PLUGIN_IDS` | 0 | 0 |
| Contract tests (utilities OFFICIAL_PLUGIN) | 49 | **112** (+63) |
| Safety tests (utilities OFFICIAL_PLUGIN) | 6 | 6 (sin cambios) |
| Total utilities tests verdes | 55 | **118** (+63) |
| Corpus fixtures (`v2/compatibility/*.pipeline.kts`) | 24 | **29** (+5) |
| `CompatibilityCorpusTest` methods | 26 | **31** (+5) |

## Artefactos del Slice 2

### Production code (todos en `pipeline-step-sdk/utilities/`)

```
src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/
├── domain/
│   ├── ReadYamlInput.kt           (S2.1)
│   ├── ReadYamlOutput.kt          (S2.1, incluye sealed YamlDocument)
│   ├── WriteYamlInput.kt          (S2.2)
│   ├── WriteYamlOutput.kt         (S2.2)
│   ├── FindFilesInput.kt          (S2.3)
│   ├── FindFilesOutput.kt         (S2.3)
│   ├── ZipInput.kt                (S2.4)
│   ├── ZipOutput.kt               (S2.4)
│   ├── UnzipInput.kt              (S2.5)
│   └── UnzipOutput.kt             (S2.5)
└── step/
    ├── CoreUtilsReadYamlKey.kt
    ├── CoreUtilsReadYamlCodec.kt
    ├── CoreUtilsReadYamlStepDefinition.kt
    ├── CoreUtilsWriteYamlKey.kt
    ├── CoreUtilsWriteYamlCodec.kt
    ├── CoreUtilsWriteYamlStepDefinition.kt
    ├── CoreUtilsFindFilesKey.kt
    ├── CoreUtilsFindFilesCodec.kt
    ├── CoreUtilsFindFilesStepDefinition.kt
    ├── CoreUtilsZipKey.kt
    ├── CoreUtilsZipCodec.kt
    ├── CoreUtilsZipStepDefinition.kt
    ├── CoreUtilsUnzipKey.kt
    ├── CoreUtilsUnzipCodec.kt
    ├── CoreUtilsUnzipStepDefinition.kt
    └── CoreUtilsDsl.kt            (extension methods + imports DSL público)
    └── CoreUtilsStepDefinitionContributor.kt  (registro de los 8 steps)
```

### Test code

```
src/test/kotlin/dev/rubentxu/pipeline/v2/sdk/utilities/step/
└── CoreUtilsStepContractSuiteTest.kt    (118 contract + safety tests)
```

### End-to-end fixtures (`v2/compatibility/`)

```
25-yaml-roundtrip.pipeline.kts       (S2.1+S2.2 positive)
26-find-files.pipeline.kts           (S2.3 positive)
27-zip-unzip.pipeline.kts            (S2.4+S2.5 positive)
28-zip-slip-defense.pipeline.kts     (S2.5 negative: CVE-2023-32981)
29-mixed-utilities.pipeline.kts      (S2.5 mixed: 5-stage end-to-end)
```

### Documentation

```
docs/v2/07-uat/
├── S2_READ_YAML_JENKINS_REFERENCE.md
├── S2_READ_YAML_CLOSURE_RECEIPT.md
├── S2_WRITE_YAML_JENKINS_REFERENCE.md
├── S2_WRITE_YAML_CLOSURE_RECEIPT.md
├── S2_FIND_FILES_JENKINS_REFERENCE.md
├── S2_FIND_FILES_CLOSURE_RECEIPT.md
├── S2_ZIP_JENKINS_REFERENCE.md
├── S2_ZIP_CLOSURE_RECEIPT.md
├── S2_UNZIP_JENKINS_REFERENCE.md
└── S2_UNZIP_CLOSURE_RECEIPT.md
```

## Decisiones arquitectónicas relevantes (para retomar mañana)

1. **Cap-routed handler discipline**: cada Step declara
   `setOf<StepCapability>(WORKSPACE_IDENTITY_CAPABILITY)` y resuelve el
   workspace via `ctx.capabilities.get<WorkspaceIdentity>(...)`.
2. **Effects + Replay**: para los write steps (`writeYaml`, `writeJson`,
   `findFiles`?, `zip`, `unzip`) se usa
   `Effect.WRITES_WORKSPACE + ReplayPolicy.NEVER` (E-EM-11 NEVER-1).
3. **`overwrite=false` por defecto** (Jenkins default): `writeJson`,
   `writeYaml`, `zip` lanzan typed USER failure si el target existe y
   `overwrite` no es `true`.
4. **Symlinks nunca seguidos en read-side**:
   `Files.walk(base)` sin `FOLLOW_LINKS` (findFiles, zip's
   `materialiseGlob`).
5. **Zip Slip (CVE-2023-32981)**:
   - **Lado archive-creation** (`core-utils.zip`): cada source path se
     resuelve contra el workspace root; paths absolutos fuera del
     workspace son rechazados antes de leer bytes.
   - **Lado extraction** (`core-utils.unzip`): per-entry canonical-path
     containment check. Entries con `..`, `/` al inicio, o backslash son
     rechazadas antes de escribir cualquier byte.
   - Probado por 3 contract tests negativos + fixture 28 negativo (splice
     de nombre de entry en LFH + CDH + EOCD offset adjustment).
6. **Jenkins-compat glob fallback** (`findFiles`, `zip`):
   `buildMatcher(glob)` envuelve dos matchers NIO (con y sin prefijo
   `**/`) porque NIO's `PathMatcher("glob:**/*.txt")` requiere que
   `**` consuma ≥1 segmento.
7. **`YamlDocument` ADT** (S2.1+S2.2): el scripting host del binario
   `pipelinek` no resuelve imports de `kotlinx.serialization.*` desde
   los `.pipeline.kts`. Por eso el DSL público usa el ADT cerrado
   `YamlDocument.{Str|Integer|Real|Bool|Seq|Map}` en lugar de
   `JsonElement`.
8. **Codec pattern unificado**: `StepCodec<I>` con `encode(I) →
   EncodedStepValue`, `decode(EncodedStepValue) → I`,
   `schema(): String`. Todos los codecs (incluidos los de S2) producen
   envelopes durables (JSON object bien formado).
9. **Decode-time validation**: los codecs de S2 (`WriteYaml`,
   `UnzipOutput`) rechazan envelopes inválidos en `decode` con typed
   `USER` failure — p.ej. `UnzipOutput` rechaza envelopes con cero o más
   de un campo populado.

## Hallazgos relevantes para futura sesión

- **Bug típico de kotlinx.serialization JSON-null**: `?.jsonPrimitive?.content`
  sobre un campo `JsonNull` devuelve la string `"null"`, no Kotlin `null`.
  Adaptar el decoder para tratar `JsonNull` y ausente como equivalentes
  (visto en S2.2 y S2.5). Patrón a recordar para futuros codecs.
- **`ZipOutputStream` no sanitiza nombres en write**: acepta
  `ZipEntry("/tmp/evil.txt")` y lo emite al archivo. La defensa está
  en el lado read (canonical-path containment en unzip).
- **JDK no expone Unix mode bits de `ZipEntry`**: no podemos detectar
  symlink-as-entry por mode; confiamos en name-based checks. Out of
  scope para S2 (deuda tracked bajo Slice 2 debt).
- **`ZipOutputStream` usa DEFLATE por defecto**: para tests que
  corrompen un byte del data section y esperan detectar CRC mismatch
  (no inflater error), hay que forzar `setMethod(STORED)` y poblar
  manualmente `entry.size`, `entry.compressedSize`, `entry.crc`.
- **Fixture script host no resuelve `kotlinx.serialization.*`**: el
  `YamlDocument` ADT es la solución. Cualquier futuro Step que reciba
  JSON complejo desde el DSL debe exponer una ADT sealed o un
  `writeXxxRaw(text: String)` que evite la dependencia del scripting
  host con kotlinx.serialization.

## Decisiones de S2 que NO se implementaron (deuda explícita)

- **Símbolos de Unix mode en unzip**: parsear el extra field manualmente
  para detectar `0xA1ED` (Unix symlink) y rechazar. La defensa actual
  basada en nombre es suficiente para los casos comunes pero un atacante
  sofisticado podría emitir un symlink cuyo nombre no contiene `..`.
- **Compresión en unzip**: el JDK `ZipFile` descomprime automáticamente;
  no añadimos flags de configuración. Si un atacante planta un zip bomb
  (ratio de compresión altísimo), los caps `MAX_ENTRIES` /
  `MAX_ENTRY_BYTES` / `MAX_TOTAL_BYTES` ya mitigan.
- **Charsets en unzip**: aceptamos sólo UTF-8. Si un entry tiene bytes
  no-UTF-8 válidos, los emitimos como `String(bytes, UTF_8)` — eso es
  comportamiento actual. No testeado explícitamente.

## Próximo paso al retomar mañana (orden recomendado)

1. **L4 full corpus run**: `./gradlew -p v2 :pipeline-application:test
   --tests 'CompatibilityCorpusTest.*'` — confirmar que los 31
   fixtures (01-29 + 23-readfile + 24-utilities-roundtrip + el método
   `allCorpusFixturesAreDiscoverable`) están verdes.
2. **Round gate L5**: `./gradlew -p v2 check` (incremental, baseline
   actual ~120s). Si pasa, el Slice 2 entero está CERTIFICADO en L5.
3. **Planificar Slice 3**: el Slice 2 está cerrado y certificado. El
   próximo slice debe definirse según el roadmap
   (`docs/v2/05-roadmap/ROADMAP_MIGRATION_LPR.md`). El Slice 2 cubrió
   YAML + findFiles + zip/unzip. Siguiente bloque probable del
   roadmap: artefactos (archive, fingerprint), credenciales (withCredentials
   ya certificado en fixture 14), o notificaciones.
4. **Actualizar este handoff** cuando se cierre Slice 3.

## Referencias rápidas

- `v2/AGENTS.md` (reglas del proyecto, fencing de testing, AGENTS.md rule
  "REFERENCE IMPLEMENTATION RESEARCH" con end-of-WU closure checklist)
- `v2/docs/v2/07-uat/S2_*_JENKINS_REFERENCE.md` (5 docs)
- `v2/docs/v2/07-uat/S2_*_CLOSURE_RECEIPT.md` (5 receipts)
- `v2/compatibility/{25..29}-*.pipeline.kts` (5 fixtures)
- `v2/pipeline-step-sdk/utilities/` (production code)
- `v2/pipeline-step-sdk/utilities/src/test/.../CoreUtilsStepContractSuiteTest.kt`
  (118 tests)
- `v2/pipeline-application/src/test/.../CompatibilityCorpusTest.kt`
  (5 nuevos methods: `fixture25YamlRoundtrip` ... `fixture29MixedUtilities`)

## Riesgos abiertos (a tener en cuenta al retomar)

- El método `CompatibilityCorpusTest.fixture29MixedUtilities` es sensible
  a `build/utils/mix/` residual entre ejecuciones. El fixture hace
  `rm -rf build/utils/mix` al inicio. Si se cambia el layout del fixture,
  mantener este cleanup.
- Fixture 28 (Zip Slip defense) crea el archivo `abcdefghijklmn` en el
  workspace (origen del cual se construye el zip benigno) y después
  crea `build/utils/malicious.zip`. Si se corre manualmente y luego se
  hace commit sin `git clean`, queda ruido.
- `lib-files-0.36.0.jar` y `utilities-0.36.0.jar` son versiones internas
  del classpath — no tocar versiones en `build.gradle.kts` sin revisar
  el versionado coordinado.
