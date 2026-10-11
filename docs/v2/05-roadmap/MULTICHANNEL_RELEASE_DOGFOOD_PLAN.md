# Plan de ejecución D0–D5 — dogfooding de release y distribución multicanal

**Estado:** PROPOSED / NOT_STARTED. **Secuencia operativa única:** `ROADMAP.md` RP-6, WU-RP-060..065. **Código observado al redactar:** `main` @ `87d7f2ef23959edb9a0313390c910e0468813f00` (2026-09-23); reconciliar Git, SESSION_POINTER y CI antes de actuar. **Prerequisito inviolable:** RP-5 GO del producto local para el SHA/artifact objetivo. Este documento no crea otra cola ni reabre RP-4/5; no iniciar otra WU mientras el puntero señale WU-RP-046.

## 1. Resultado de usuario y frontera

Al crear un **tag estable protegido** `vX.Y.Z`, GitHub Actions verifica el commit del tag, instala un **runner `pipelinek` anterior estable y certificado**, ejecuta la lógica de release en `ci/release.pipeline.kts`, recoge evidencias del motor y exige validación externa. Sólo entonces produce una **GitHub Release** con el ZIP exacto certificado, SHA-256, SBOM, manifiesto y notas. El evento `release:published` inicia la distribución, **sin compilar otra vez**, hacia mise, asdf y (cuando exista onboarding) SDKMAN. Cada canal tiene estado y recibo independientes.

**No mover a YAML** stages, Steps, branches, condiciones ni lógica de publicación. `pipeline.kts` y sus scripts hermanos siguen siendo la única fuente del comportamiento; los workflows de Actions son bootstrap, permisos/identidad y verificación externa. No añadir un Step core específico para los instaladores ni implementar RP-8/9.

## 2. Puntos de partida: reutilizar, no reconstruir

- `.github/workflows/lpr0-ci.yml` ya tiene N1 bootstrap independiente, N2 `pipelinek` en dogfood y N3 oráculo externo. Extender, no clonar ese test.
- `pipeline.kts` raíz ya expresa build/tests/package. Caracterizar **contra el runner instalado elegido**: su API actual puede diferir de la de una release anterior. No usar su `whenCondition("env.LPR_PUBLISH ...")` como autorización de publicación hasta contar con semántica probada. La autorización final es del gate externo/protected environment.
- `.github/workflows/release.yml` es **V1 en cuarentena**. Nuevo `release-v2.yml` separado; no reactivar V1.
- `.github/workflows/sdkman-publish.yml`, `scripts/release/sdkman-publish.sh` y `scripts/release/sdkman-install-uat.sh` existen. Corregirlos por WU sin cambiar el protocolo de producto.
- `v0.39.0` es una release pública existente y su ZIP tiene SHA-256 conocido, **no garantiza** compatibilidad del DSL futuro ni certifica el HEAD. El tag previo usado como runner debe elegirse y fijarse mediante una UAT de compatibilidad, nunca por ser simplemente «el más reciente».
- `DISTRIBUTION_RELEASE_SPEC.md` y ADR-0089 (propuesto) describen autoridad de ZIP único; `CERTIFICATION_PROTOCOL.md` y `UAT_DISTRIBUTION_SDKMAN.md` siguen vinculantes.

## 3. Arquitectura mínima: un artefacto, varios adaptadores

```text
push tag protegido vX.Y.Z
    │
    ▼
Actions: checkout SHA exacto + JDK 21 + instalar/verificar runner anterior
    │                                  runner SHA256 fijado
    ▼
ci/release.pipeline.kts (compila/valida el commit etiquetado)
    │
    ├── T0–T5 / UAT de producto / fallo intencional
    ├── construir distZip 2 veces y comparar bytes
    └── probar ZIP candidato mediante instalación independiente
    │
    ▼
Oráculo externo + entorno publish protegido → GitHub Release ZIP + SHA + SBOM + manifest
    │
    ▼
release:published → fan-out idempotente, SIN build
    ├── mise: instalar ZIP directo de GitHub, UAT limpia
    ├── asdf: plugin por URL, instalar mismo ZIP, UAT limpia
    └── SDKMAN: publicar → instalar catálogo oficial → default (si onboarding)
    │
    ▼
recibo por canal y por artefacto; retomar SOLO canales pendientes
```

Separa `runnerVersion`/`runnerDigest` (herramienta que coordina) de `targetVersion`/`targetCommit`/`artifactDigest` (objeto publicado). El SHA del runner no es el SHA del proyecto; no registrar uno como sustituto del otro.

## 4. Contrato de identidad y estados

Metadatos inmutables de release: `targetTag`, `targetCommit`, `targetVersion`, `runnerVersion`, `runnerDigest`, `artifactName`, `artifactDigest`, `sbomDigest`, `certificationReceipt`, `releaseUrl`, `profile`. No colocar secretos en manifiesto ni recibos.

Modelo ADT para el orquestador **del canal**, no para el motor de Steps:

```kotlin
sealed interface ChannelStatus {
    data object NotConfigured : ChannelStatus
    data class BlockedExternal(val reason: Blocker) : ChannelStatus
    data class Failed(val phase: Phase, val evidence: EvidenceRef) : ChannelStatus
    data class Published(val evidence: EvidenceRef) : ChannelStatus
    data class InstalledVerified(val evidence: EvidenceRef) : ChannelStatus
    data class DefaultVerified(val evidence: EvidenceRef) : ChannelStatus // solo SDKMAN
}
```

No usar `success: Boolean` que confunda «canal no configurado» con «publicado y verificado». `GitHubZipReady`, `MiseReady`, `AsdfReady`, `SdkmanReady` son evidencias distintas. Un canal bloqueado no vuelve a construir ni reetiquetar el ZIP; reintenta desde una release inmutable.

## 5. WUs, dependencias y cortes verificables

### WU-RP-060 · Runner y plan de release (D0)

Caracterizar runner estable instalado de ZIP público en runner limpio. Fijar tag y SHA-256 del runner por una fuente revisada y **no autocalcular la confianza usando el propio runtime sin verificación externa**. Verificar compilación de `ci/release.pipeline.kts` con ese binario; si falla, reducir DSL del publicador a primitivas ya certificadas o elegir otro runner previamente certificado. Distinguir `LPR_PUBLISH` de permiso real de publicación; eliminar del contrato de autorización cualquier `whenCondition` no certificado. Salida: contrato de inputs e identidad, fixture de tag inválido y prueba externa de compatibilidad del runner, sin publicar.

### WU-RP-061 · Tag → release canónica (D1)

Nuevo workflow `release-v2.yml`: `push.tags: ['v*']` con validación estricta de versión estable, repo/ref protegido, SHA exacto, autorización y permisos mínimos. El tag es el **disparador de certificación**, no una orden de publicar. Ejecutar todos los checks obligatorios del PRODUCT-GATE sobre **la misma candidata** y un ZIP reproducible. Publicar solo tras GO y con entorno protegido `release`, sin secretos de canal en etapas de compilación/PR. `workflow_dispatch` puede recuperar una candidata existente, pero debe validar tag/SHA/release y no reconstruir ni mover un artefacto publicado. Una release/asset preexistente con digest diferente => STOP; no sobrescribir. El workflow V1 permanece en cuarentena.

### WU-RP-062 · mise directo (D2)

UAT del backend GitHub Releases de mise contra ZIP por nombre/patrón exacto y `strip_components` certificado con su versión concreta; documentar instalación fijando versión y Java 21. `mise install` debe dejar `bin/pipelinek` ejecutable y ejecutar version/doctor/validate/run. No asumir que `mise use pipelinek` funciona sin alias: instalación directa vía backend GitHub es el canal inicial; solicitud al registro central es futura, separada y opcional. **No publicar a una supuesta API de mise ni modificar su registro por cada tag.**

### WU-RP-063 · asdf externo mínimo (D3)

Repo independiente `Rubentxu/asdf-pipelinek`, instalable por URL con asdf real. Scripts mínimos `bin/list-all`, `bin/download`, `bin/install` (y `bin/latest-stable` sólo si el protocolo probado lo necesita). Descubrir tags/releases estables, seleccionar el ZIP exacto, verificar SHA-256 de autoridad, desempaquetar con estructura segura y permisos. Probar `asdf plugin test`, instalación pinneada, latest/upgrade, 404, ZIP malicioso y checksum falso. Una nueva versión se descubre sin tocar el plugin; el índice `asdf-vm/asdf-plugins` es otro gate opcional, no condición para instalación por URL.

### WU-RP-064 · SDKMAN reutilizado (D4)

Mantener scripts/acciones existentes. Corregir `sdkman-install-uat.sh`: dos fixtures con `steps { echo(...) }` que no representan la sintaxis actual del DSL; cargar `sdkman-init.sh` **en la shell hija** antes de `command -v sdk`; verificar código de salida real, resultado y ruta SDKMAN del ejecutable; comparar checksum del ZIP público por descarga y evidencia del catálogo sin confundirlo con hash del árbol extraído. Corregir `sdkman-publish.sh` para que `version visible: False` implique FAIL tras sondeo acotado. El actual workflow `sdkman-publish.yml` escucha `release:published`: antes del fan-out, evitar esa activación automática no admitida o convertirla en despacho condicional explícito. El proceso sigue `publish` sin default → UAT sobre catálogo **oficial** → promote default → consultar default. Ausencia de candidato/credenciales = `BLOCKED_EXTERNAL` con evidencia; no convertir un error auténtico de credenciales presentes en skip exitoso. Nunca promocionar por respuesta HTTP aislada.

### WU-RP-065 · Fan-out, reintentos y receipts (D5)

Workflow `distribution-publish.yml` para `release:published` o invocación de recuperación con tag; descarga y verifica assets **antes** de iniciar cualquier canal. Un `ci/distribution.pipeline.kts` compatible con runner estable organiza los adaptadores existentes sin guardar tokens en logs. Garantizar concurrencia por `tag+canal`, idempotencia observable, reintento de un solo canal, fallo parcial y oráculo externo. Los estados de cada canal aparecen en receipts nuevos, nunca en una reescritura de receipts antiguos. No bloquear GitHub/mise/asdf por onboarding SDKMAN; no anunciar `ALL_CHANNELS_READY` si algún canal está bloqueado o sin probar.

## 6. Superficie que NO se construye

- No editar motor durable, StepRegistry, `uppercase`, compilador o DSL para este evolutivo.
- No generar stages/Steps ni control flow en YAML.
- No reproducir Jenkins ni diseñar workers gRPC.
- No mezclar propuesta de overlay YAML de RP-7 con la distribución (solo compatibilidad de la CLI realmente instalada).
- No crear un mirror público SDKMAN; el mirror local existente solo da evidencia local.
- No crear un segundo empaquetador para mise/asdf.

## 7. Gates de integración

**Antes del primer commit de implementación:** confirmar cierre RP-5 en `SESSION_POINTER.md` y recibo del SHA; comprobar identidad vigente de WU, branch, CI y esquema de artefacto; reconciliar numeración de WUs y de ADR con el HEAD.

**Durante desarrollo:** tests afectados por adaptador y UAT local reales; no forzar full suite tras cada edición de docs o scripts. **Antes de merge/release:** checks completos exigidos por impacto y PRODUCT-GATE sobre el SHA/ZIP candidato, más pruebas negativas y de instalación limpia de cada canal habilitado. No tratar `NOT_RUN` ni `BLOCKED_EXTERNAL` como PASS.

**Promoción pública:** GitHub Release creada solo tras certificación; SDKMAN `default` solo tras `sdk install` oficial verde. Los instaladores externos se prueban con los bytes de la release, no con un ZIP recién compilado.

## 8. Dependencias y fuentes vivas

- Roadmap y gates: `ROADMAP.md`, `CERTIFICATION_PROTOCOL.md`, `PRODUCTION_READY_UAT_MATRIX.md`, `UAT_DISTRIBUTION_SDKMAN.md`.
- Distribución y contratos: `DISTRIBUTION_RELEASE_SPEC.md`, ADR-0089 (propuesto), `.github/workflows/lpr0-ci.yml`, `pipeline.kts`, `.github/workflows/sdkman-publish.yml`, `scripts/release/sdkman-{publish,install-uat}.sh`.
- SDKMAN: https://sdkman.io/vendors ; https://github.com/sdkman/sdkman-release-action ; https://github.com/sdkman/sdkman-db-migrations (read-only; **no PR nuevo**).
- mise: https://mise.jdx.dev/dev-tools/backends/github.html ; https://mise.jdx.dev/registry.html .
- asdf: https://asdf-vm.com/plugins/create.html ; https://github.com/asdf-vm/asdf-plugins .
- Ejemplos de distribución: https://github.com/scala/scala3/blob/main/.github/workflows/publish-sdkman.yml ; https://github.com/ModelJars/modeljars/blob/main/.github/workflows/sdkman-publish.yml .

**Nota de autoridad:** estos enlaces son referencias técnicas; la semántica de flags/backend/instaladores debe confirmarse durante sus respectivas UAT, no darse por probada por esta documentación.
