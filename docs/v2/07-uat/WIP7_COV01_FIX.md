# WIP-7 — §1.3 COV-01 corregido: direccionamiento Kover por Gradle path

**Fecha:** 2026-10-10
**Bloque:** B1 · v0.48.0-rc2
**Veredicto:** defecto **reproducido y corregido**. Sin cambio de umbrales. Sin cambio de formato.

## Defecto reproducido

`v2/build.gradle.kts:248-302` definía dos mapas con paths slash-separated:

```kotlin
val koverRuleMinByModule = mapOf(
    "pipeline-step-sdk/runtime" to 70,
    "pipeline-step-sdk/utilities" to 65,
    "pipeline-step-sdk/scm-git" to 55,
    ...
)
val koverRuleDisabledByModule = setOf(
    "pipeline-step-sdk/api",
    "pipeline-step-sdk/processor",
    "pipeline-step-sdk/junit",
    ...
)
when {
    project.name in koverRuleMinByModule -> { ... }
    project.name in koverRuleDisabledByModule -> { ... }
}
```

`project.name` para un proyecto anidado (`:pipeline-step-sdk:runtime`) es solo el leaf (`"runtime"`),
NO el path completo. Resultado: ningún match, `when` cae al default, **ninguna regla koverVerify se
aplica a ningún subproyecto anidado**.

### Verificación empírica (init script)

Antes del fix, todos los subproyectos anidados mostraban:

```
: :pipeline-step-sdk:runtime -> name=runtime -> NONE  (debería ser MIN(70))
: :pipeline-step-sdk:utilities -> name=utilities -> NONE (debería ser MIN(65))
: :pipeline-step-sdk:scm-git -> name=scm-git -> NONE  (debería ser MIN(55))
: :pipeline-step-sdk:api -> name=api -> NONE          (debería ser DISABLED)
...
```

Tras el fix:

```
COV01_CHECK path=:pipeline-step-sdk:runtime      rule=MIN(70)    ✓
COV01_CHECK path=:pipeline-step-sdk:utilities    rule=MIN(65)    ✓
COV01_CHECK path=:pipeline-step-sdk:scm-git      rule=MIN(55)    ✓
COV01_CHECK path=:pipeline-step-sdk:api          rule=DISABLED   ✓
COV01_CHECK path=:pipeline-step-sdk:files        rule=DISABLED   ✓
COV01_CHECK path=:pipeline-step-sdk:http         rule=DISABLED   ✓
COV01_CHECK path=:pipeline-step-sdk:junit        rule=DISABLED   ✓
COV01_CHECK path=:pipeline-step-sdk:workflow-control rule=NONE  (no en el mapa — sin cambio)
```

## Fix aplicado

```kotlin
val koverRuleMinByModule = mapOf(
    ":pipeline-artefacts-local"       to 85,
    ":pipeline-binding-factory"       to 85,
    ":pipeline-domain"                to 75,
    ":pipeline-events"                to 70,
    ":pipeline-event-harness"         to 70,
    ":pipeline-step-sdk:runtime"      to 70,
    ":pipeline-step-sdk:utilities"    to 65,
    ":pipeline-step-sdk:scm-git"      to 55,
    ":pipeline-credentials-executor"  to 55,
    ":pipeline-scripting-kotlin24"    to 55,
)
val koverRuleDisabledByModule = setOf(
    ":pipeline-step-sdk:api",
    ":pipeline-step-sdk:processor",
    ":pipeline-step-sdk:junit",
    ":pipeline-step-sdk:http",
    ":pipeline-step-sdk:files",
    ":pipeline-scripting-api",
    ":pipeline-credentials-api",
    ":pipeline-credentials-local",
    ":pipeline-credentials-multipart",
    ":pipeline-testkit",
    ":pipeline-architecture-tests",
    ":pipeline-application",
)
when {
    project.path in koverRuleMinByModule -> { ... }
    project.path in koverRuleDisabledByModule -> { ... }
}
```

- Las keys usan `:path:leaf` (formato Gradle) en vez de `path/leaf`.
- La selección usa `project.path` en vez de `project.name`.
- Sin cambio de umbrales. Sin cambio de formato durable.

## Stale entry

`:pipeline-step-sdk:processor` está en el disabled set pero el proyecto no existe en el árbol
actual (los step-sdk subproyectos son `api, files, http, junit, runtime, scm-git, utilities,
workflow-control`). No es un bug; el `when` simplemente no matchea nada. Lo dejo registrado
para limpieza futura; no es bloqueante.

## Validación

Comando de verificación: `cd v2 && ./gradlew -q --init-script /tmp/cov01_verify.gradle.kts help`
(imprime `COV01_CHECK path=… name=… rule=…` para cada subproyecto).

Sin cambio observable en cobertura actual: los subproyectos afectados NO estaban midiendo antes
y ahora SÍ se aplica la regla. Si la cobertura actual está por debajo del umbral, koverVerify
fallará. Esto es el comportamiento esperado y correcto (anti-regression guard).

## Consecuencias

- COV-01 está **cerrado**. Sin umbrales relajados, sin formato nuevo.
- Los subproyectos `pipeline-step-sdk:{runtime,utilities,scm-git}` ahora se vigilan al 70%/65%/55%.
- Los subproyectos `pipeline-step-sdk:{api,files,http,junit}` ahora están explícitamente marcados como informational.
- Si koverVerify falla tras este cambio, la causa es que la cobertura real está por debajo del
  umbral declarado — **es lo correcto**.

## Próximo paso

WIP-8: aplicar fix conjunto para OUT-01/OUT-02 cuando se defina la política de migración. Si
no se decide en B1, se arrastra como deuda clasificada.

WIP-9: §1.4 cierre de observabilidad (Output/Event Plane; cursores; redaction).

WIP-10..12: integración y release.