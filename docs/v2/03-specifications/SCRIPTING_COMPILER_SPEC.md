# Kotlin Scripting & Compiler Specification

## 1. Decisión

Kotlin Custom Scripting es el frontend productivo principal de `.pipeline.kts`, aun estando sus APIs públicas marcadas Experimental. El riesgo se acepta porque:
- la tecnología scripting está ampliamente probada;
- Gradle Kotlin DSL usa el scripting host en producción;
- la API experimental se encapsula;
- el runtime fija una versión conocida de Kotlin;
- existe fallback de compilación convencional y compatibility corpus.

## 2. Baseline

Primera línea certificada:

```text
Kotlin: 2.4.10
JVM target: 21
JDK runtime: 21 inicialmente; validar 25 como matriz adicional
DSL API: 2.0
```

Kotlin 2.4 hace estables los context parameters. La custom scripting API sigue experimental; no cruza el adapter.

## 3. Ports

```kotlin
interface PipelineScriptEngine {
    suspend fun compile(
        source: PipelineSource,
        environment: CompilationEnvironment
    ): CompilationResult

    suspend fun evaluate(
        artifact: CompiledPipeline,
        runtime: PipelineRuntime
    ): PipelineExecution
}
```

```kotlin
interface PipelineCompiler {
    suspend fun compile(request: CompileRequest): CompileResult
}
```

## 4. Adapter Kotlin 2.4

Módulo: `pipeline-scripting-kotlin24`.

Único módulo autorizado para imports de `kotlin.script.experimental.*` y host internals necesarios.

Responsabilidades:
- `@KotlinScript` definition;
- compilation configuration;
- imports/capabilities;
- explicit classpath;
- diagnostics mapping;
- compiler cache;
- evaluate artifact;
- source digest;
- Kotlin version compatibility.

## 5. Classpath policy

Prohibido en producción:

```kotlin
dependenciesFromCurrentContext(wholeClasspath = true)
```

El classpath se calcula desde:
1. Kotlin stdlib/required runtime API;
2. Pipeline DSL API;
3. Plugin lockfile;
4. libraries aprobadas;
5. generated Step façades.

Toda entrada tiene digest/version y participa en `CompilationCacheKey`.

## 6. Dependency policy

`@file:DependsOn` arbitrario no forma parte del modo seguro por defecto. Dependencias deben declararse en manifiestos/plugins lockeados. Puede existir un modo developer local explícito, no usado en producción.

## 7. Compilation cache

Key mínima:

```text
sha256(
  pipelineSourceDigest,
  kotlinCompilerVersion,
  scriptingAdapterVersion,
  dslApiVersion,
  pluginLockDigest,
  compilerOptionsDigest,
  approvedClasspathDigest
)
```

Cache entry registra diagnostics y binary artifact. Nunca reutilizar si una dimensión cambia.

### Estado de la entrada (decisión registrada 2026-10-18, commit `40449447`)

La entrada implementada guarda **el artefacto binario**, identificado por la clave
completa `(version, value)` con la etiqueta de versión en el nombre del fichero. No
persiste los diagnostics de compilación.

Consecuencia medida y acotada: una reutilización emite `CompilationFinished` con los
diagnostics de la evaluación, no los de la compilación. Cold `pipelinek validate`
imprime `Compilation finished with 1 info`; warm imprime `Compilation finished`.

Por qué es aceptable, y por qué no se persisten:

- Una compilación **con errores nunca llega al almacén**: falla antes de `store`. Los
  diagnostics de error —los únicos que `ConsoleFamilyLines.compilationFinished` usa
  para ramificar, y los únicos que decide un resultado de run— no se pueden perder por
  reutilización.
- Lo que sí se pierde son diagnostics informativos de una compilación que tuvo éxito.
- Persistirlos exigiría un formato propio de serialización con versión y política de
  corrupción para información que ningún consumidor funcional lee. El coste de
  compatibilidad no se paga con ese retorno.

Lo que sí garantiza la entrada: una dimensión que cambia cambia la clave, así que un
artefacto jamás se reutiliza bajo una identidad que no le corresponde; y un artefacto
dañado o ilegible degrada a recompilación en vez de envenenar el almacén.

## 8. Version strategy

Adapters versionados por línea:

```text
pipeline-scripting-api
pipeline-scripting-kotlin24
pipeline-scripting-kotlin25   # futuro
```

Un release certifica una combinación. No se promete “cualquier Kotlin 2.x”.

## 9. Compatibility corpus

CI ejecuta:
- Kotlin stable certificada: blocking;
- próxima patch/minor stable: pre-adoption;
- RC: advisory/blocking para release upgrade;
- EAP: informational early-warning.

Snapshots:
- compile success/failure;
- diagnostics normalized;
- DSL structure;
- event trace de escenarios deterministas.

## 10. BTA

Build Tools API se mantiene como adapter/spike estratégico, no como sustituto inmediato de Scripting Host. BTA y Scripting resuelven capas distintas. Cuando sea públicamente apropiada para third-party build tools, puede implementar `PipelineCompiler`.

## 11. Direct compiler fallback

Debe existir una ruta operativa de emergencia basada en `kotlinc`/compiler process aislado para:
- diagnóstico;
- incompatibilidad puntual del host adapter;
- comparar outputs;
- recuperación de una release.

## 12. Compiler plugin

FIR/IR no es dependencia del happy path. Si se usa, sólo para diagnostics/instrumentation que no puedan expresarse con language features + KSP y siempre detrás de un feature flag/version gate.
