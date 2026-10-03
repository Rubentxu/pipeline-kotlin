# S4-IDENTITY I2a — el compilador emite un ámbito estructural para el cuerpo de un `for`

> **Alcance.** Este recibo certifica **un slice**, no un bloque, y no es un
> `STEP-CERT` ni un `PRODUCT-GATE`. No habilita ninguna afirmación sobre durabilidad
> de bucles más allá de lo que se midió aquí, y ver §5.

## 1. Qué se cierra

I1 (falsificación) dejó medido, contra el cableado productivo, que dentro de un
`for` real lo único que distingue una iteración de la siguiente es
`ScriptedScope.nextOrdinal` — un contador de llegadas en memoria de proceso. Con
argumentos distintos el `input fingerprint` rechaza el reuso; con argumentos
**idénticos** la reanudación salta en silencio un efecto que el pipeline debía y
deja huérfana su fila de journal.

La mitad de runtime que podía llevar estructura ya existía y ya estaba probada
(`ScriptedScope.scoped`, `ScriptedScopeTest`). La mitad de compilador no existía:
un grep de `pipeline-scripting-kotlin24` no devolvía ninguna gestión de bucles, y
el test llamado `generated loop scopes…` no generaba nada porque su Kotlin llamaba
a `scoped` a mano.

I2a suple esa mitad y sólo esa mitad.

## 2. Cambio

| Fichero | Cambio |
|---|---|
| `pipeline-scripting-api/…/ScriptedExecutionApi.kt` | `ScriptedLoopScope` (nuevo) + `ScriptedSourceMapping.Mapped.loopScopes` |
| `pipeline-scripting-kotlin24/…/KotlinScriptedSourceMapper.kt` | `visitForExpression` registra los `for` de cuerpo de bloque |
| `pipeline-scripting-kotlin24/…/ScriptedSourceLowering.kt` | `rewriteRuntimeReturningCalls` → `rewrite(text, calls, loopScopes)`: una pasada de ediciones resueltas contra el texto original |

Emisión resultante:

```kotlin
for (i in listOf("a", "b")) {     // for (i in …) {
steps.scoped(ScriptedDynamicScopeId("loop:s4identity:1:1")) {
    …
}
}
```

### 2.1 Por qué hubo que rehacer el reescritor

El reescritor anterior resolvía la posición de cada llamada contra el buffer **ya
reescrito**, en orden inverso. Eso solo funciona mientras no haya envoltorios: un
`scoped` que abarca un cuerpo desplaza todos los offsets interiores, así que la
resolución por offset contra un buffer mutante y el ámbito estructural no pueden
 coexistir.

La solución no introduce autoridad nueva: se resuelven todas las ediciones una vez
contra el texto inmutable y se aplican de atrás hacia delante. Las inserciones de
bucle son inserciones puras (`length = 0`), que es lo que hace que los bucles
anidados caigan bien sin lógica especial.

## 3. Defecto encontrado y corregido durante el slice

El primer `scopeId` de este slice era:

```kotlin
ScriptedDynamicScopeId("loop:$loopParameter[$loopParameter]")
```

con un KDoc que prometía `loop:i[0] loop:i[1] loop:i[2]`. Tenía dos fallos:

1. **Prometía un índice que no puede existir.** El segundo slot era el nombre del
   parámetro. La cadena es una constante en el fuente generado, evaluada **antes**
   de que el cuerpo corra; nada en el código emitido conoce el número de iteración.
2. **No llevaba posición.** Al no incluir `sourceId:línea:columna`, dos bucles
   `for (i in …)` distintos en un mismo archivo componían el **mismo**
   `dynamicScopePath`, fusionando call sites distintos en una identidad durable.

Corregido a:

```kotlin
ScriptedDynamicScopeId("loop:${location.sourceId.value}:${location.line}:${location.column}")
```

El KDoc del tipo declara ahora, en el propio API, que I2a **no** cierra el
defecto de I1 y por qué el sufijo de iteración no es emitible aquí.

## 4. Verificación

### 4.1 Corpus dirigido

`S4IdentityLoopScopeTest`, 15 tests, 15 verdes, 0 fallos.

Cubre: offsets de llaves del cuerpo; `scopeId` posicional; **renombrar el
parámetro no cambia la identidad**; dos bucles con el mismo nombre de parámetro
reciben ids distintos; anidamiento; `while` intacto; cuerpo sin llaves intacto;
fuente sin bucles sin ámbito; **fidelidad exacta del rewriter** (el cuerpo
generado es el fuente + exactamente dos inserciones y nada más); llamada
reescrita dentro del ámbito; anidamiento abre fuera-dentro y cierra
dentro-fuera; sin bucles no hay `steps.scoped`; determinismo byte a byte; y dos
propiedades de identidad medidas contra `ScriptedRuntime` de producción (paths
distintos → `operationId` distinto; ordinal contado **dentro** del ámbito).

### 4.2 Mutaciones

| Mutación | RED | Qué demuestra |
|---|---|---|
| `M-s4i2a-1` — sin inserción de apertura | 3 | los 3 tests que dependen de la apertura |
| `M-s4i2a-2` — reemplazar el span completo del bucle en vez de insertar en las llaves | 3 | las inserciones no destruyen el cuerpo |
| `M-s4i2a-3` — `scopeId` por nombre de parámetro | 5 | identidad posicional, no colisionable |

La más fuerte es `M-s4i2a-3`: es exactamente el defecto de §3, y los dos tests que
la matan son los que fijan la propiedad de identidad.

Restauración tras cada mutación verificada con `sha256sum -c`.

### 4.3 Gate de slice

```text
cd v2 && ./gradlew :pipeline-scripting-api:test :pipeline-scripting-kotlin24:test \
                    :pipeline-application:test :pipeline-architecture-tests:test
```

| | |
|---|---|
| exit | `0` |
| resultado | `BUILD SUCCESSFUL in 27m 43s` |
| tests | 4007 |
| failures | 0 |
| errors | 0 |
| skipped | 132 |
| log | `s4i2a-slice.log` · `sha256:ee8c5f46440ec48a741614a4fba6a446795a163be35b25b28d524bc87813a9e7` |

**No** es el gate del BLOQUE 2 ni el `PRODUCT-GATE`. El exit code se leyió
inmediatamente tras el comando, sin pipe.

## 5. Lo que I2a NO cierra

El ordinal sigue siendo un contador de llegadas y sigue sin ser durable. El
silent-no-op medido en I1 **sigue abierto**.

Lo que cambia es que el ordinal queda acotado a un bucle identificado en vez de
flotar en un contador global, que es la estructura que I2b necesita para
sustituirlo por un camino de ocurrencia determinista. Leer este slice verde como
"la identidad de bucles es durable" sería exactamente el tipo de afirmación no
ganada que S4-A1b ya tuvo que deshacer una vez.

## 6. Hallazgo colateral para I2b

`ScriptedSourceLocation.loopScope(iteration)` y `blockScope(blockName)` están
**sin ningún uso productivo**: se definen, y sólo los ejercita
`ScriptedSourceLocationTest`. Es el mismo patrón que I1 encontró con `scoped` — la
autoridad existe y está probada, pero nadie la produce.

Consecuencia para I2b: `loopScope(iteration)` **no es alcanzable tal cual desde el
lowering**, porque exige un `Int` de iteración que el texto generado no puede
conocer (§3.1). Resolverlo requiere decidir de dónde sale ese valor, y esa decisión
es de *ownership*, no de implementación. Queda planteada como STOP antes de
escribir código.

## 7. Convergencia (verificación de la revisión arquitectónica)

Comprobado contra el código, no asumido:

| Autoridad afirmada | Estado real |
|---|---|
| `RuntimeScriptedStepFacade` | existe — `CompiledScriptedEntryPoint.kt:34` |
| `ScriptedFrontendForm` | existe — `ScriptedFrontendRunner.kt:124` |
| `StepProviderMetadata` / `PluginReleaseRef` | existen — `pipeline-domain` |
| `sh` va por `invokeTyped` | **ya es así** — `CompiledScriptedEntryPoint.kt:90-128`; S4-A1b movió las tres formas de `scope.invokeAt` a `call()` |
| un solo camino a producción | se mantiene — `ScriptedFrontendRunner` inyecta `RegistryScriptedShellRuntime(invoker)`, que es el **mismo** `ScriptedRegistryInvoker` tras una adaptación de firma `ScriptedOperationRuntime`; no es una segunda columna vertebral |

I2a no introduce autoridad nueva: extiende `ScriptedDynamicScopeId` y
`ScriptedScope.scoped`, que ya eran las autoridades.
