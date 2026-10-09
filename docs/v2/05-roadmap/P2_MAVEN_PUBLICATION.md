# P2 — Publicación Maven rc: estado medido (2026-10-08)

## Lo que funciona, con evidencia ejecutada

```bash
cd v2 && ./gradlew publishToMavenLocal \
  :pipeline-domain:publishSdkPublicationToMavenLocal \
  :pipeline-events:publishSdkPublicationToMavenLocal \
  :pipeline-scripting-api:publishSdkPublicationToMavenLocal \
  :pipeline-output:publishSdkPublicationToMavenLocal
# exit=0, BUILD SUCCESSFUL in 1s, 25 actionable tasks: 15 executed, 10 up-to-date
```

Artefactos realmente producidos en `~/.m2/repository/dev/rubentxu/pipeline/v2/0.47.0`:

```text
5b3dff6cbc6b0672a50b9e69fe0fa1d4b762ec11baea4fd204be7874dd04a85e  pipeline-domain-0.47.0.jar
84144086271daf86dedbd4b416bb5cb9ad0f893e5273865b5a1b99664a3ac97b  pipeline-domain-0.47.0.pom
20e3a3a8a065fb3755bdd119b0f48bd80f69abfefef2c135ba670def5246cb62  pipeline-events-0.47.0.jar
36afbcd514e177981deecac826f88bd8ebb01e3089ab1853f593427dc31b1d7b  pipeline-output-0.47.0.jar
a757646b8a1528ec9340b53296f35cce2da1fd36979df84cbc722afd124deb52  pipeline-scripting-api-0.47.0.jar
d03ad617b7e274f8569882bf66f4b3ad0db5fba57f8ebeee2134de0f9d5ec3f5  pipeline-sdk-bom-0.47.0.pom
```

Cada módulo publica `jar` + `pom` + `.module` (Gradle module metadata). El BOM publica
`pom` + `.module`. La cadena de publicación **funciona de extremo a extremo sin red**.

## El hueco real: `sdk` no es un repositorio remoto

Los cinco módulos publicados declaran el mismo destino:

```kotlin
// v2/<module>/build.gradle.kts, bloque publishing.repositories
maven {
    name = "sdk"
    url = uri(rootProject.layout.buildDirectory.dir("sdk-repo"))
}
```

`build/sdk-repo` es un **directorio dentro de `build/`**. No es Nexus, ni GitHub Packages, ni
un repo Maven por HTTP. Por tanto:

- `publishAllPublicationsToSdkRepository` existe y funciona, pero **deposita en un directorio
  descartable**. No publica en ninguna parte.
- La tarea existe, se ejecuta, y su nombre sugiere una publicación remota que no ocurre. Eso es
  exactamente el modo de fallo "verde por construcción": un agente que ejecutara
  `publish...ToSdkRepository` y viera `BUILD SUCCESSFUL` concluiría que los artefactos están
  publicados, y no lo están.

**Nada de esto es un defecto que yo introduzca**: es el estado actual, medido. La conclusión es
que publicar el rc a un repositorio real es trabajo de configuración pendiente, no un bug.

## Lo que falta para el rc remoto

| Pieza | Estado | Quién lo resuelve |
|---|---|---|
| Publicación a Maven local | **funciona, verificado arriba** | — |
| URL del repositorio remoto | **no existe** en ningún `build.gradle.kts` | persona (el destino: Nexus / GitHub Packages) |
| Credenciales de publicación | no inyectadas | persona |
| Firma GPG de los artefactos | no configurada (`signing` ausente) | persona (clave y política) |
| Versión `0.48.0-rc1` en los módulos | pendiente (P3) | este repo |

## Consecuencia para ROADMAP A

La frontera P2/P3 se define así, y no antes:

```text
P2  = la cadena de publicación funciona          → VERIFICADO
P3  = construir 0.48.0-rc1 y publicarla en un destino remoto
     → BLOQUEADA: falta URL + credenciales + política de firma
```

Un rc se puede **construir y verificar** sin credenciales. Un rc se puede **publicar**
solo cuando la persona suministre destino y credenciales. Nada de esto altera
`PRODUCT-GATE`, que sigue `BLOCKED_EXTERNAL` por ADR-0105.

La sustitución de `build/sdk-repo` por una URL real debe hacerse de modo que el destino
local siga disponible para verificación offline; no se sustituye por un repo remoto y se
pierde la prueba local.