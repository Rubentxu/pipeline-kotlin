# Recibo — E2-05, mitad de almacenamiento: caché de scripts compilados

**Estado:** STEP-CERT por recibo inmutable. Enlaza a `40449447`.
**Cierra:** `IMPLEMENTATION_BACKLOG.md:30` E2-05 (ambas mitades).
**Forma declarada por:** `docs/v2/03-specifications/SCRIPTING_COMPILER_SPEC.md` §7 "Compilation cache".
**Condición satisfecha:** `docs/v2/07-uat/CACHE_KEY_V2_MIGRATION_PLAN.md` §"Follow-up this plan does NOT cover" — *"the store must key on `(version, value)` and must not read a `v2` value with `v1`'s recipe"*.
**Fecha:** 2026-10-18

## Qué entrega

Un almacén durable del artefacto binario de una compilación, y la decisión de reutilización construida sobre él.

```text
v2/pipeline-scripting-kotlin24/src/main/kotlin/.../CompiledScriptCache.kt
    enum CacheMissReason { ABSENT, UNREADABLE, NOT_JVM_SCRIPT }
    sealed interface CacheLookup { Hit(script) ; Miss(reason) }
    interface CompiledScriptCache { load ; store ; object Disabled ; fromEnvironment(env) }
    class JarCompiledScriptCache(root)          // un jar por clave
v2/pipeline-scripting-kotlin24/src/main/kotlin/.../CachingJvmScriptingHost.kt
    override eval(...)                          // consulta, reutiliza o compila
```

Conmutador: `PIPELINEK_SCRIPT_CACHE_DIR`. Ausente o en blanco → `Disabled`, es decir, el comportamiento de toda instalación existente. La opción es explícita porque el producto no tiene una convención de usuario donde colocar una caché, e inventarla falla en una dirección distinta en cada entorno (HOME de sólo lectura, CI enjaulada, contenedor efímero). Quitar ese default más adelante es un cambio de una línea.

## Tres decisiones, y por qué son así

**1. El miss es un valor con causa, no un `null`.** `ABSENT` es la primera ejecución; `UNREADABLE` es un almacén que existe y no es de fiar. Mantenerlos aparte es lo que hace visible "la caché no hizo nada" en lugar de indistinguible de una caché fría — y esa distinción decide si un usuario reporta un bug.

**2. La identidad llega como argumento del constructor.** `CachingJvmScriptingHost` no recalcula la clave. La calcula `Kotlin24ScriptingHost`, una vez por compilación, y ya la emite en `CompilationFinished`. Recalcularla aquí crearía dos autoridades para "cuál es la identidad de esta compilación": una divergencia serviría el artefacto equivocado sin síntoma visible — el defecto de segunda autoridad que DR-1 prohíbe. También deja el host sin estado mutable, que es lo que la ley de contexto inmutable explícito (CTX-P) exige de quien participa en la ejecución.

**3. Todo fallo degrada a miss, nunca a excepción.** Una caché de compilación es una preocupación de rendimiento y jamás de corrección. Un almacén capaz de tumbar un pipeline es el almacén equivocado, y por eso `load` captura `Throwable` y `store` falla en silencio. Un artefacto escrito se mueve a su nombre definitivo sólo con `ATOMIC_MOVE` (o `REPLACE_EXISTING` si el sistema de ficheros no lo soporta): una muerte entre escritura y movimiento deja un `.tmp` que `load` nunca lee, mientras que un movimiento sin ese paso dejaría un jar truncado bajo el nombre real.

## El defecto que las pruebas unitarias no podían ver

El diseño declaraba que todo fallo degrada a miss. El UAT efímero sobre el binario instalado refutó esa promesa:

```text
jar truncado a 936 de 3747 bytes  -> loadScriptFromJar devuelve valor  -> llega como Hit
la evaluación lanza RuntimeException("Compiled script is not loaded yet")
como un hit nunca almacena, la entrada envenenada sirvió ese mismo fallo en CADA ejecución posterior
exit 2 ; luego warm656ms y aun así exit 2
```

Causa: `KJvmCompiledScriptLazilyLoadedFromClasspath.getScriptOrError()` **lanza**; la cadena vive en `kotlin-scripting-jvm-host-2.4.10.jar`, localizada con `grep` sobre los jars y confirmada descompilando. Mi primer arreglo miraba el canal equivocado (capturaba `ResultWithDiagnostics.Failure`) y la fila siguió roja con el diagnostic exacto.

Reparación: `catch (unusable: RuntimeException)` alrededor de la reutilización, que recompila y deja que el almacén sobrescriba la entrada dañada para que la siguiente ejecución empiece sana. El `catch` es `RuntimeException` y no `Throwable` a propósito: un `Error` (heap agotado, un fallo de linkage que la JVM no puede superar) debe propagarse, no convertirse en silencio en una recompilación. Un cuerpo de script que lanza llega como `ResultValue.Error`, no como excepción, así que un throwable aquí es infraestructura — y por eso el alcance narrowed es correcto y no una suposición.

**Frontera de cobertura, declarada y no insinuada:** un modo de daño que hiciera que el artefacto cargara *y* evaluara a un resultado equivocado pero exitoso no está aquí tratado, y no puede detectarse desde dentro del host. Dos clases están medidas —ilegible al cargar, y lanzable al evaluar— y son las dos de las que degrada el almacén.

## Evidencia (unida a este SHA)

Resultado leído del XML JUnit tras borrar el canario antes; `^e:` fue `0`.

```text
CompiledScriptCacheTest   tests=9 skipped=0 failures=0 errors=0
                          timestamp=2026-10-18T15:56:05.079Z
```

Las fuentes del módulo son idénticas a `40449447`; después de esa ejecución sólo cambiaron documentos, lo que no puede afectar al módulo. El XML es la evidencia, no `BUILD SUCCESSFUL`.

Cuatro mutaciones, cada una atribuida a la fila que tumba. **Cada una se verificó aplicada antes de leer su resultado** (conteo de sitios de llamada / grep), porque una fila verde que sobrevive a una mutación no aplicada no prueba nada; y cada restauración se verificó por `sha256`:

| # | Mutación | Filas que tumba | Aserción que falla |
|---|---|---|---|
| M1 | quitar la etiqueta de versión del nombre | 1 | `two versions of the same value must not share a file name` |
| M2 | estrechar el `catch` de `load` a `IOException` | 2 | `a corrupt artifact is UNREADABLE rather than an exception` + la de fallo abierto |
| M3 | consultar el almacén e ignorar el hit (duplicar el cuerpo de compilación en la rama `Hit`) | 1 | `expected: <1> but was: <2>` — en el conteo de almacenamientos, es decir, la fila de reutilización |
| M4 | estrechar el `catch` de recuperación a `IllegalStateException` | 1 | `a damaged stored artifact heals instead of poisoning the next run` |

Fila CONTROL: `the disabled cache leaves compilation untouched` pasa antes y después, así que "el resultado es correcto" no se puede satisfacer haciendo que toda compilación tenga éxito.

Restauraciones verificadas: `6bac894f…`, `d36d9943…`, `67e72da4…`.

## UAT efímero — la pieza que las unitarias no sustituyen

Las nueve filas inyectan el almacén. Nada probaba que `fromEnvironment()` llegara a producción. Medido sobre el binario instalado, con el fixture en `/tmp` (borrado después; nunca dentro del repositorio):

```text
validate frío   4824 ms   -> 1 jar escrito, v2-<64hex>.jar
validate warm    696 ms / 687 ms   -> exit 0
sin la variable de entorno   4881 ms   -> cero ficheros creados (opt-in respetado)
script distinto             4788 ms   -> segundo jar (identidades distintas)
run durable frío  5462 ms  outcome=success
run durable warm  2049 ms / 2019 ms  outcome=success
```

Las duraciones son contexto, **no** la afirmación: la afirmación es que el almacén se consultó, que respondió hit, y que el resultado y su identidad no cambiaron. Una fila que afirmara "se volvió más rápido" sería satisfied por un fallo silencioso (HARNESS FIDELITY §3).

## Desviación declarada: la entrada guarda el binario, no los diagnostics

§7 dice *"Cache entry registra diagnostics y binary artifact"*. La entrada implementada guarda el artefacto binario. Consecuencia medida y acotada:

```text
validate frío   "Compilation finished with 1 info"
validate warm   "Compilation finished"
```

Por qué se acepta en lugar de inventar un serializador:

- Una compilación **con errores nunca llega al almacén**: falla antes de `store`. Los diagnostics de error —los únicos que `ConsoleFamilyLines.compilationFinished` usa para ramificar, y los únicos que deciden un resultado de run— no se pueden perder por reutilización.
- Lo que sí se pierde son diagnostics informativos de una compilación que tuvo éxito.
- Persistirlos exigiría un formato propio de serialización, con versión y política de corrupción, para información que ningún consumidor funcional lee. El coste de compatibilidad no se paga con ese retorno.

Registrado en §7 como "Estado de la entrada (decisión registrada 2026-10-18)".

## Refos declined, con el motivo junto a donde un lector irá a buscarlo

- **`CompiledScriptJarsCache` no se usa como segunda autoridad.** Serializa bien, pero deriva la identidad de la fuente y de la configuración de compilación: dos claves para una compilación, que es exactamente DR-1. Se delega el códec a `saveToJar`/`loadScriptFromJar` (el mismo códec) y se pasa la clave como parámetro. El motivo está en el KDoc de `JarCompiledScriptCache`, no en un comentario de diff.
- **Sin serializador de diagnostics a medida.** Motivo en la sección anterior; el coste se paga aquí una vez y no se vuelve a pagar en cada reutilización.

## Orden de aserciones = atribución

La fila de curación afirma primero `isSuccess`, luego la identidad, y sólo al final el tamaño restaurado. Ese orden es el que hace que un fallo diga *qué* Garantía se rompió: si el orden fuese el inverso, un fallo de compilación se leería como un fallo de curación.

## Closure check de fin de unidad de trabajo

```text
Reference implementation consulted:  ninguno aplicable — una caché de compilación es una
                                      función de identidad más un almacén, no un comportamiento
                                      portado. La única referencia externa consultada fue
                                      SCRIPTING_COMPILER_SPEC.md §7 (spec interna, el contrato
                                      declarado de 7 dimensiones), cuya lista se comparó con las
                                      dimensiones que el producto puede realmente leer.

Behaviour adopted:                   el host reutiliza el artefacto de una compilación idéntica en
                                      lugar de recompilar; todo fallo del almacén degrada a
                                      compilación, y una entrada dañada se sobrescribe en vez de
                                      envenenar la siguiente ejecución.

Intentional deviations:              (a) la entrada persiste el artefacto binario, no los
                                      diagnostics de compilación (§7 "Estado de la entrada"),
                                      medido y justificado arriba;
                                      (b) no se persisten los diagnostics informativos que una
                                      reutilización deja de emitir;
                                      (c) la caché es opt-in por variable de entorno, sin default,
                                      porque un default inventado falla distinto en cada entorno.

Security implications reviewed:      la caché se consulta en cada compilación, así que una
                                      entrada manipulada podría ejecutar bytecode ajeno: de ahí
                                      que la escritura sea atómica y que un artefacto ilegible
                                      degrade en vez de servirse. La identidad va con la etiqueta
                                      de versión en la ruta, de modo que un artefacto v1 no puede
                                      servirse bajo una clave v2. No se lee ninguna credencial ni
                                      ruta de entrada del usuario más allá del directorio que él
                                      mismo configura.

Tests demonstrating the contract:    v2/pipeline-scripting-kotlin24/src/test/kotlin/
                                      dev/rubentxu/pipeline/v2/scripting/CompiledScriptCacheTest.kt
```

## Receipt binding

Evidencia capturada contra `40449447c563e47923077fa952982e78cfdc9ffb`. Un recibo es evidencia de su propio SHA y no lo hereda ningún cambio posterior.

No ejecutado, y declarado: el gate completo `check` y la batería UAT. La batería UAT es del harness (`AGENTS.md` §"Frontera de responsabilidad", puntos 1 y 6); un `check` de repo es el gate interno L5 de contratos, y este WU toca dos ficheros de un módulo cuya cobertura directa ya es verde.