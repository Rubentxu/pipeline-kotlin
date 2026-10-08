# P3 — La candidata `0.48.0-rc1` no se puede construir: bloqueador medido (2026-10-08)

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

## Estado

| Ítem | Estado |
|---|---|
| P3 bump de versión a `0.48.0-rc1` | **BLOCKED** — bloqueador medido arriba |
| P2 cadena de publicación | VERIFIED |
| Publicación remota | BLOCKED_EXTERNAL (URL, credenciales, firma) |
| `PRODUCT-GATE` | `BLOCKED_EXTERNAL` (ADR-0105), con independencia de todo esto |