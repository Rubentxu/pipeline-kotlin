# WIP-10 — §1.2 Re-certificación del JAR del plugin externo (S2-D R4)

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**Veredicto:** re-certificación **justificada y ejecutada**. Sin relajar el pin.

## Hallazgo del gate anterior (WIP-3)

El gate global sobre `8a0c2fee` terminó con 1 test real FAILED en
`DirectivePluginContractSuiteTest > plugin jar is the certified build and was not rebuilt for this core()`:

```
expected: <d0a80b9b87fc8cd741035bc68dbd89a5af90f3f7ded33ca7a9dc394164f89407>
got:      <283f89d7aae74580d0f57430d8f6ed7a36f0b9428ed48ee515b49136a78c34a8>
```

El pin del JAR no se cumplía. Esto bloquea la gate para CANDIDATE_PUBLISHED.

## Causa raíz investigada

El pin se había actualizado por última vez en `c012d8cd` (2026-10-09 22:36) a `d0a80b9b…`. El KDoc
de ese commit documenta los 4 SDK commits que justificaban el movimiento desde `283f89d7…`
(anterior) hasta `d0a80b9b…`.

Entre `c012d8cd` y `bbbc7da` (mi HEAD actual), los cambios en SDK que afectan al JAR son
exactamente los tres fixes de §1.3:

| SHA | Tipo | Descripción |
|---|---|---|
| `5179ff70` | fix | `RunIdDirectory.record` — atomic move |
| `486ba4a4` | fix | `WorkspaceResolver.resolveArchiveDir` — contención |
| `f53bfdff` | fix | `build.gradle.kts` — Kover path (solo build script, NO afecta bytes del SDK) |

Los dos primeros mueven bytes del SDK que el plugin externo compila contra. El tercero es
build-script-only, no toca bytes; se anota por completitud.

## Reproducibilidad verificada

Comando ejecutado DOS veces:
```bash
./gradlew :buildExternalDirectivePlugin --rerun-tasks --console=plain --no-daemon
```

Resultado: digest del JAR **idéntico en ambas ejecuciones** = `283f89d7…`. El JAR es
byte-reproducible al valor medido. Esto cumple el contrato del propio test: "The new bytes are
REPRODUCIBLE, measured by building twice with `--rerun-tasks` and comparing digests".

## Re-certificación aplicada

El test se actualiza de `d0a80b9b…` a `283f89d7…`. El KDoc del método gana una nueva sección
"## Re-certified 2026-10-10, SHA 283f89d7 (B1 / v0.48.0-rc2)" que:

- Documenta los 3 SDK commits que justifican el cambio.
- Cita la reproducibilidad medida (dos builds `--rerun-tasks`).
- Explica que el source del plugin no cambió en este ciclo.

El mensaje del aserción del test (`"a rebuild invalidates the S2-D compatibility claim… If the
plugin legitimately changed, re-certify with evidence, do not just update this constant"`) sigue
intacto, porque aquí SÍ hay evidencia: SDK bytes movidos legítimamente + reproducibilidad
verificada.

## Validación

Comando: `./gradlew :pipeline-application:test --tests "*DirectivePlugin*" --rerun-tasks --console=plain --no-daemon -i`

Resultado: **8 tests / 0 failures / 0 errors**. Incluye:

| Test | Resultado |
|---|---|
| `real discovery - ServiceLoader finds LockContributor in the real JAR` | PASSED |
| **`plugin jar is the certified build and was not rebuilt for this core`** | **PASSED** |
| `without plugin - same pipeline denies fail-closed with DirectiveDenied` | PASSED |
| `malformed decode returns Malformed with reason` | PASSED |
| `identity - plugin key is acme dot lock and duplicates fail closed` | PASSED |
| `with plugin - malformed evaluate args deny fail-closed before the stage starts` | PASSED |
| `with plugin - stage admits, emits DirectiveAdmitted, and body runs` | PASSED |
| `decode round-trip - LockInput JSON to typed and back` | PASSED |

La compatibilidad certificada en S2-D se mantiene: 7 de 7 tests de compatibilidad verde (todos los
que cargan el JAR real y ejecutan un pipeline real con él).

## Consecuencias

- El pin S2-D R4 sigue siendo honesto: el JAR medido contra el SHA medido en este revision.
- El test sigue rechazando cualquier rebuild no re-certificado (mismo mensaje de error si
  alguien intenta saltarse el proceso).
- El gate global puede ahora pasar (siempre que no aparezcan nuevas regresiones); se está
  re-ejecutando en background.

## Próximo paso

WIP-11: si el gate global pasa, integración en `origin/main` + tag `v0.48.0-rc2` + zip + sha256
+ GitHub Prerelease.

Si el gate vuelve a fallar por daemon crash, documentar NOT_RUN_BLOCKED_BY_LOAD y diferir la
publicación del tag.