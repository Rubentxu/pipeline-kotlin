# PK — deriva de ABI publicada sin revisar (medida 2026-10-18)

**Estado: `BLOCKED_REVIEW`.** No es un defecto de este bloque; es deuda de puerta que
`detekt` llevaba ocultando. No se ha regenerado ningún `.api` de este recibo.

## Por qué existe este recibo

`v2/build.gradle.kts:455-459` dice qué compra el dump de BCV, y no es detectar roturas:

> «Its internal callers are inside this build, so a removed public type fails
> `compileKotlin` immediately and loudly. **What the dump buys is the review — a diff
> nobody has to be shown.**»

Es decir: el `.api` existe para que un humano mire. Este recibo es ese diff, materializado
para que la decisión no dependa de que alguien lance una tarea.

## Qué lo mantuvo oculto

`check` engancha `detekt` (`build.gradle.kts:373`). Mientras `detekt` falló con 104
hallazgos heredados durante 937 commits, `check` abortaba **antes** de llegar a `apiCheck`.
La deriva de ABI —incluidas 12 eliminaciones de firma pública— acumuló hasta 10 días sin
que ninguna puerta la mirara. Corregido el orden en `28a6082a`.

## Reparto medido

`./v2/gradlew -p v2 apiCheck --continue` sobre los 4 módulos con puerta.

**Sobre el instrumento, porque cambió la cifra.** Las eliminaciones coinciden entre
tres métodos (`diff -u`, diferencia de multiconjuntos y `difflib` posicional): **−11** y
**−1**. Las adiciones sólo coinciden entre dos: el `diff -u | grep -c '^+[^+]'` que
usaba la primera vez **subcuenta**, porque una línea añadida vacía es exactamente `+` y no
casa con `[^+]`. Las cifras de adiciones de esta tabla salen de `difflib`, y las dos
primeras (+5, +16) se midieron **antes** de regenerar sus dumps, por lo que ya no son
re-derivables del árbol.

| módulo | .dump ABI vigente | +adiciones | −eliminaciones | disposición |
| --- | --- | --- | --- | --- |
| `:pipeline-output` | `dfaa9918` 2026-10-12 | 5 | 0 | regenerado, compatible |
| `:pipeline-credentials-api` | `ac67a79b` 2026-10-05 | 16 | 0 | regenerado, compatible |
| `:pipeline-scripting-api` | `072f9a31` 2026-10-06 | 20 | **1** | **escalado aquí** |
| `:pipeline-domain` | `dfaa9918` 2026-10-12 | 229 | **11** | **escalado aquí** |

La línea de reparto es la compatibilidad hacia atrás, no el módulo: una
**adición** no rompe a un consumidor y por eso su dump se regenera; una **eliminación**
cambia lo que un consumidor externo resuelve y por eso se escala.

## Qué es cada eliminación

Las 12 no son un bloque homogéneo, y leerlas como una sola lista sería exactamente el
error que este recibo evita.

**`:pipeline-scripting-api` (1) — un portador tipado, no una pérdida.**
`CacheKey$Companion$v2.compute(String×4)` pasó a ser
`compute(CompilationIdentity)`. Las cuatro dimensiones siguen existiendo; lo que cambió
es que ya no viajan como cuatro cadenas posicionales. El commit es `3099c082`
(*events-spine + cache-key versioning*), y el sentido del cambio es **el mismo** que pide
el punto 3 del ROADMAP: un carrier con nombre en lugar de valores sueltos. Es
binariamente incompatible para un consumidor compilado contra el dump viejo, pero no
pierde capacidad.

**`:pipeline-domain` (11) — cuatro grupos con riesgo distinto.**

| grupo | nº | clases | qué ocurrió |
| --- | --- | --- | --- |
| tipo público que desaparece | 1 | `PluginManifestRejection` | la interfaz **dejó de existir** |
| constructor/copy de un caso | 5 | `PluginAdmissionResult$Refused` | el caso `Refused` cambió de payload |
| `data class` re-mapeada | 3 | `PluginManifest` | cambia el constructor sintético y `copy-RBxDhtE` |
| accessor / singleton | 2 | `TaskExecutionResult.getSucceeded`, `TrustMetadata$Unverified.INSTANCE` | medidos, y **ninguno de los dos es una pérdida** |

Atribución: `PluginManifestRejection` y el `Refused` que lo portaba entran en
`eaa57d30` (*B3.2 step 1*) y salen en `981039de` (*dedupe plugin artifacts by their
measured bytes*). `PluginManifest` se remapea en `961bda11` (*el manifest deja de ser un
objeto y pasa a ser un documento*).

Los dos primeros grupos son eliminaciones reales de superficie pública. El tercero es
ruido de `data class` regenerado por un cambio de campos. Y el cuarto se midió en vez de
suponerse:

- `getSucceeded()` desapareció de `TaskExecutionResult`
  (`DurableTaskRuntime.kt:63-76`), pero `exitCode` sigue siendo la autoridad y
  `timedOut`/`cancelled` están al lado. Un consumidor deriva `succeeded` de
  `exitCode == 0`; es la pérdida de un **accessor de conveniencia**, no de una capacidad.
- `TrustMetadata$Unverified.INSTANCE` desapareció porque `Unverified` dejó de ser un
  `object` y pasó a ser un `data class` con carga propia (`Unverified(reason, origin)`,
  `TrustMetadata.kt:28`). Eso es una **ampliación** del tipo, no una eliminación.

## Lo que hay que decidir

Las 12 eliminaciones, textuales y sin reinterpretar:

- 	public final fun compute (Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ldev/rubentxu/pipeline/v2/scripting/CacheKey;

- 	public final fun getSucceeded ()Z
- 	public fun <init> (Ldev/rubentxu/pipeline/v2/domain/step/PluginManifestRejection;)V
- 	public final fun component1 ()Ldev/rubentxu/pipeline/v2/domain/step/PluginManifestRejection;
- 	public final fun copy (Ldev/rubentxu/pipeline/v2/domain/step/PluginManifestRejection;)Ldev/rubentxu/pipeline/v2/domain/step/PluginAdmissionResult$Refused;
- 	public static synthetic fun copy$default (Ldev/rubentxu/pipeline/v2/domain/step/PluginAdmissionResult$Refused;Ldev/rubentxu/pipeline/v2/domain/step/PluginManifestRejection;ILjava/lang/Object;)Ldev/rubentxu/pipeline/v2/domain/step/PluginAdmissionResult$Refused;
- 	public final fun getRejection ()Ldev/rubentxu/pipeline/v2/domain/step/PluginManifestRejection;
- 	public synthetic fun <init> (ILdev/rubentxu/pipeline/v2/domain/identity/ResourceRef;Ldev/rubentxu/pipeline/v2/domain/step/PluginReleaseRef;Ldev/rubentxu/pipeline/v2/domain/step/PipelineKApiRange;Ljava/lang/String;Ljava/util/Set;Ldev/rubentxu/pipeline/v2/domain/step/Delivery;Ldev/rubentxu/pipeline/v2/domain/step/TrustMetadata;Ldev/rubentxu/pipeline/v2/domain/step/PluginContributions;Lkotlin/jvm/internal/DefaultConstructorMarker;)V
- 	public final fun copy-RBxDhtE (ILdev/rubentxu/pipeline/v2/domain/identity/ResourceRef;Ldev/rubentxu/pipeline/v2/domain/step/PluginReleaseRef;Ldev/rubentxu/pipeline/v2/domain/step/PipelineKApiRange;Ljava/lang/String;Ljava/util/Set;Ldev/rubentxu/pipeline/v2/domain/step/Delivery;Ldev/rubentxu/pipeline/v2/domain/step/TrustMetadata;Ldev/rubentxu/pipeline/v2/domain/step/PluginContributions;)Ldev/rubentxu/pipeline/v2/domain/step/PluginManifest;
- 	public static synthetic fun copy-RBxDhtE$default (Ldev/rubentxu/pipeline/v2/domain/step/PluginManifest;ILdev/rubentxu/pipeline/v2/domain/identity/ResourceRef;Ldev/rubentxu/pipeline/v2/domain/step/PluginReleaseRef;Ldev/rubentxu/pipeline/v2/domain/step/PipelineKApiRange;Ljava/lang/String;Ljava/util/Set;Ldev/rubentxu/pipeline/v2/domain/step/Delivery;Ldev/rubentxu/pipeline/v2/domain/step/TrustMetadata;Ldev/rubentxu/pipeline/v2/domain/step/PluginContributions;ILjava/lang/Object;)Ldev/rubentxu/pipeline/v2/domain/step/PluginManifest;
- public abstract interface class dev/rubentxu/pipeline/v2/domain/step/PluginManifestRejection {
- 	public static final field INSTANCE Ldev/rubentxu/pipeline/v2/domain/step/TrustMetadata$Unverified;
