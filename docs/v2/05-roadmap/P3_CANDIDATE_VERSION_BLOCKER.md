# P3 — La candidata `0.48.0-rc1` no se puede construir: bloqueador medido (2026-10-08)

> **CORRECCIÓN (2026-10-08, segunda pasada).** La primera versión de este documento
> concluyó que `SemVer` necesitaba un campo prerelease y presentó cuatro opciones
> para enseñárselo. **Eso es incorrecto y va en contra de un diseño ya implementado
> y ya tipado en este repositorio.** La conclusión correcta está al final, en
> "Por qué la opción A es la peor de las cuatro". Se conserva el análisis
> intermedio porque es la evidencia que produjo la corrección, y porque un lector
> tiene que poder ver por qué se propone algo que luego se corrige.

P3 no se ejecutó porque el bump de versión **deja el runtime sin arranque**. No es una
estimación; hay un RED ejecutado.

## El RED

```bash
cd v2 && ./gradlew :pipeline-application:test --tests '*RuntimeApiVersionPrereleaseProbeTest*'
# exit=1, BUILD FAILED in 23s
# XML: tests="2" skipped="0" failures="1" errors="0"
```

```
RuntimeApiVersionPrereleaseProbeTest > the 0_48_0-rc1 candidate version is
readable by the runtime parser FAILED
AssertionFailedError: RuntimeApiVersion.parse rejected the candidate version
'0.48.0-rc1'. ... ==&gt; expected: not &lt;null&gt;
```

No hay error de compilación: el RED falla por la razón esperada, que es lo único que lo
válida.

## La cadena, medida de punta a punta

```text
v2/build.gradle.kts:75        version = "0.47.0"
        │  generateVersionResource escribe  version=$versionValue  verbatim
        ▼                            (pipeline-application/build.gradle.kts:57-73)
.../pipelinek-version.properties   version=0.48.0-rc1
        │  RuntimeApiVersion.current() → parse(raw)
        ▼
RuntimeApiVersion.parse           parts.size != 3 → null
        │
        ▼
current() lanza IllegalStateException("version '0.48.0-rc1' read from ... is not
MAJOR.MINOR.PATCH. The SDK admits plugin apiRanges against a three-component SemVer
and nothing else, so a version it cannot read is a version it cannot safely compare.")
```

El parser es explícito (`RuntimeApiVersion.kt:82-88`): `parts.size != 3` devuelve `null`.
`0.48.0-rc1` tiene tres puntos y tres componentes, pero el último es `0-rc1`, y
`"0-rc1".toIntOrNull()` es `null`. Por tanto **`RuntimeApiVersion.current()` lanza**, y con
él caen el subcomando `pipelinek version` y la admisión de plugins.

El propio mensaje del error es la posición oficial del código: el SDK admite `apiRange`
contra un SemVer de tres componentes **y nada más**.

## Por qué no se puede rodear

`SemVer` (`v2/pipeline-domain/.../step/SemVer.kt:11-19`) es `major/minor/patch`, y su
`toString()` es `"$major.$minor.$patch"`: **no tiene dónde escribir un sufijo prerelease**.
No es un bug de parseo, es que el tipo no representa el valor.

Y el precedente existe: `v2/pipeline-release/.../ProductVersion.kt` documenta `0.44.0-rc1`,
y `DistributionIdentityProbeTest` construye `0.43.0-rc1`. El sistema de release de
distribución **sí** maneja prerelease; el de admisión de plugins **no**. Son dos partes
distintas de la misma versión, y solo una la sabe leer.

## El rango `apiRange` no salva la situación

Los cuatro módulos del SDK declaran `apiRange = [0.47.0, 0.49.0)`, y
`PipelineKApiRange.accepts` compara solo `major.minor.patch`. O sea: **`0.48.0-rc1` está
dentro del rango**. El rango lo admitiría; lo que falla es que el runtime no llega a
preguntárselo.

## Decisión de diseño pendiente

Ninguna de estas opciones es un bugfix trivial y todas cambian un contrato público, así que
**no se elige sola**:

| Opción | Qué implica | Coste |
|---|---|---|
| A. `SemVer` gana un campo prerelease | `SemVer` deja de ser de tres componentes; `toString` lo emite; hay que revisar constructores, `compareVersions`, `PipelineKApiRange` y los tests de suspecho de versión | Contractual, amplio |
| B. El resource lleva dos campos: `version` y `apiVersion` | El runtime sigue admitiendo tres componentes; el recurso pasa a tener la versión de producto y la versión de API por separado | Menor, pero crea una segunda autoridad que puede divergir |
| C. La candidata se numera sin sufijo (`0.48.0`) y el estado de rc vive solo en el tag y el prerelease | Cero cambios de runtime; contradice TARGET-VERSION CANDIDATE LAW de AGENTS.md, que exige el sufijo en las cinco superficies | Contradice una ley aceptada |
| D. El runtime parsea el prefijo y lo **descarta** para el rango, registrándolo aparte | Cambio acotado en `RuntimeApiVersion` + `SemVer` mínimo | Pierde el sufijo como dato comparable |

El precedente `0.43.0-rc1` de `DistributionIdentityProbeTest` sugiere que en su momento se
tomó una vía parecida a la A o la D para la distribución, y que el caso de admisión simplemente
nunca se encontró porque `rootProject.version` nunca llevó sufijo.

**Nada se bumpea antes de resolver esto.** Un bump que deja `pipelinek version` lanzando una
excepción sería una candidata que no arranca, y una candidata que no arranca no es candidata.

## Por qué la opción A es la peor de las cuatro

La primera pasada no miró el módulo de release antes de proponer. `SemVer` no debería aprender sufijos;
**el repo ya decidió que no los lleva**, y lo decidió por un incidente.

`v2/pipeline-release/.../ProductVersion.kt` lo dice literalmente:

> "a product version is a final SemVer triple. It MUST NOT carry a candidate suffix:
> `0.44.0-rc1` is therefore **not a valid ProductVersion**; the candidate is
> `(ProductVersion=0.44.0, CandidateId=sha256:…)`."

```kotlin
private val FINAL_SEMVER = Regex("""^(\d+)\.(\d+)\.(\d+)$""")
```

El regex rechaza cualquier sufijo, y el KDoc explica el porqué: el artefacto `0.43.0-rc1`
era internamente consistente y luego se presentó bajo identidad GA, y un gate que solo
comparaba las superficies del propio build habría dado verde sobre exactamente los bytes
que causaron el incidente. **Rechazar un sufijo en la identidad del producto es lo que
elimina la segunda identidad que había que blanquear.**

Y la candidata ya tiene su representación, en `CandidateHandoff`:

```kotlin
@SerialName("release_train")    val releaseTrain: ProductVersion,   // 0.48.0
@SerialName("candidate_sequence") val candidateSequence: Int,      // 1  "not part of SemVer"
@SerialName("product_version")  val productVersion: ProductVersion, // 0.48.0
@SerialName("candidate_id")     val candidateId: CandidateId,      // sha256:<64 hex> del ZIP
```

> "Candidate state lives entirely in this document. The product identity lives in the
> bytes ([ProductVersion]) and in the distribution manifest. Keeping them in separate
> documents is what makes promotion-rewriting structurally impossible rather than merely
> discouraged."

**`-rc1` no es una versión.** Es `releaseTrain=0.48.0` + `candidateSequence=1` +
`candidateId=sha256:…`. Ya está implementado, ya es tipado, y el digest es lo único que
identifica la candidata.

### Entonces, ¿dónde está el bloqueo real?

Si `0.48.0` es la `ProductVersion` correcta — y lo es, porque no lleva sufijo — entonces
**el bump a `0.48.0` no dispara el RED**. El RED dispara con `0.48.0-rc1`, es decir con una
identidad que el repo prohíbe por diseño.

El bloqueo de P3 no es "falta soportar prerelease". Es: **la forma `0.48.0-rc1` que P3
propuso choca con `ProductVersion` y con `CandidateHandoff`**, y ese choque es una
corrección del plan, no un defecto del runtime.

Lo que hay que medir ahora, y no se ha medido, es si el pipeline de release completo sabe
emitir una candidata sin que el sufijo entre en `rootProject.version`. Esa es la pregunta
real, y es de una línea de versión en el build, no de un cambio de contrato.

## Estado corregido

> **SEGUNDA CORRECCIÓN.** Una tercera pasada afirmó que `CandidateHandoff` era "un
> contrato completo sin productor". **También es falso.** El productor existe:
> `CandidateMaterializer.materialize()` calcula el digest, `CandidateAdmission` decide,
> y `CandidateAdmissionMain` es el entry point cableado a Gradle en
> `pipeline-release/build.gradle.kts:73` (`mainClass = CandidateAdmissionMainKt`), con
> `usage: candidate-admission <zip> <productVersion> <gitCommit> <outDir> [<candidateRef>]
> [<candidateSequence>] [<sbomPath>] [<repoRoot>]`. Verificado con 45 tests verdes.
>
> Dos afirmaciones falsas seguidas en este mismo documento. La causa es la misma en
> ambas: se deduce una ausencia a partir de un `grep` acotado en lugar de enumerar el
> `grep` sobre `*.kts` buscando "handoff" no puede encontrar un productor escrito en Kotlin.

| Ítem | Estado |
|---|---|
| Explicación "SemVer necesita campo prerelease" | **RETIRADA: incorrecta** |
| "`SemVer` con prerelease" (opción A) | **descartada**: contradice `ProductVersion` y el incidente `0.43.0` |
| Candidata `0.48.0-rc1` como versión | **descartada**: el estado rc vive en `CandidateHandoff` |
| Mecanismo de candidata (materialize → admit → handoff) | **EXISTE, cableado a Gradle y verificado: 45/0** |
| Bump `rootProject.version` a `0.48.0` | **EJECUTADO** (2026-10-09), verificado verde |
| Publicación remota | BLOCKED_EXTERNAL (URL, credenciales, firma) |
| `PRODUCT-GATE` | `BLOCKED_EXTERNAL` (ADR-0105), con independencia de todo esto |
## Bump ejecutado y verificado (2026-10-09)

El owner decidió que la versión vive en la configuración de Gradle y solo ahí. Bump aplicado:

```kotlin
// v2/build.gradle.kts:87
version = "0.48.0"
```

MINOR, no PATCH, siguiendo la convención que el propio fichero documenta para `0.46.0 -> 0.47.0`
("MINOR porque el módulo añadido cambia lo que un consumidor puede construir contra"). Medido:

```text
módulos AÑADIDOS  en el train:  :pipeline-sdk-bom          (publicado → cambia lo consumible)
módulos RETIRADOS en el train:  :pipeline-step-sdk:processor
```

Una sola línea editada. Ninguna otra superficie necesitó tocarse, y esa es la prueba de que
`rootProject.version` es la autoridad única y no una entre varias.

### Evidencia (escalera L0 → L1 → L2)

```text
L0  ./gradlew :pipeline-application:compileTestKotlin :pipeline-release:compileTestKotlin
    exit=0, BUILD SUCCESSFUL, sin errores de compilación

L1  ./gradlew :pipeline-application:test --tests '*RuntimeApiVersionDriftTest*'
                                           --tests '*BuiltPluginManifestArtifactTest*'
    exit=0, BUILD SUCCESSFUL in 27s
    RuntimeApiVersionDriftTest         tests="2" skipped="0" failures="0" errors="0"
    BuiltPluginManifestArtifactTest    tests="12" skipped="0" failures="0" errors="0"
    → el runtime lee 0.48.0 y el artefacto construido con rango [0.47.0, 0.49.0) lo admite

L2a ./gradlew publishToMavenLocal (los 5 módulos publicados)
    exit=0, BUILD SUCCESSFUL
    pipeline-domain-0.48.0.{jar,module,pom}   pipeline-sdk-bom-0.48.0.{module,pom}

L2b ./gradlew :pipeline-release:test --tests '*DistributionIdentity*'
                                          --tests '*SourceProvenance*' --tests '*CandidateContinuity*'
    exit=0, BUILD SUCCESSFUL in 7s
    DistributionIdentityProbeTest$ReadingRealBytes        tests="4" skipped="0" failures="0"
    DistributionIdentityProbeTest$LaunderedArtifact       tests="3" skipped="0" failures="0"
    DistributionIdentityProbeTest$UnobservableSurfaces    tests="4" skipped="0" failures="0"
    DistributionIdentityProbeTest$AgainstRealCheckedOutArtifacts
                                       tests="2" skipped="1" failures="0" errors="0"
    DistributionIdentityVerdictTest (4 clases)           11 tests, 0 fallos
    SourceProvenanceTest                                 tests="9" skipped="0" failures="0"
    CandidateContinuityFitnessTest                       tests="3" skipped="0" failures="0"
```

**Sobre el `skipped="1"`, sin trampas:** es
`the 0.43_0 artifact is internally consistent, so the incident was downstream`, y su motivo
registrado es `Assumption failed: 0.43.0 artifact not present in this checkout`. Es un canary
histórico que exige un artefacto que este checkout no tiene. **No se cuenta como pasado.** El
otro test de la misma clase (`every checked-out distribution ZIP has internally agreeing ZIP
surfaces`, 2.903 s) sí se ejecutó.

### Lo que este bump NO hace

- **No crea tag.** Ningún `v0.48.0` existe.
- **No publica en remoto.** `sdk` sigue siendo `build/sdk-repo`.
- **No certifica.** `PRODUCT-GATE = BLOCKED_EXTERNAL`.
- **No abre ni cierra la candidata.** El estado rc requiere `CandidateHandoff` con
  `candidateSequence=1` y `candidateId=sha256:<digest del ZIP>`, y eso se emite tras construir
  el ZIP, no antes.

### El probe del sufijo cambia de significado

`RuntimeApiVersionPrereleaseProbeTest` queda `@Disabled` con otro motivo: `0.48.0-rc1` **sigue
siendo ilegal por diseño**, y esa negativa ahora es el comportamiento especificado. Su valor
pasa a ser de guarda contra regresión de la ley: si algún cambio hiciera que el runtime
aceptara `0.48.0-rc1`, que ese probe se pusiera verde sería una **alerta, no una victoria**.
