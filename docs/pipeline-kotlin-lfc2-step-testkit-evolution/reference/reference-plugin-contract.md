# Reference external plugin contract

Primer plugin: deliberadamente simple para probar arquitectura.

Suggested semantic example:

```kotlin
@PipelineStep(id = "example.uppercase")
context(output: OutputCapability)
suspend fun uppercase(text: String): String =
    text.uppercase()
```

El API real debe ajustarse al SDK finalmente aprobado.

## Acceptance sequence

1. Publicar SDK artifacts a temp Maven repo.
2. Independent Gradle build resuelve sólo API pública.
3. KSP genera descriptor/registry/manifest.
4. JAR contiene generated resources.
5. RealPipelineExtension inicia distribución.
6. Plugin se instala/descubre por mecanismo productivo.
7. `.pipeline.kts` usa typed façade.
8. Compile emite un Invoke `example.uppercase`.
9. Registry resuelve definition.
10. Missing capability variant falla before effects.
11. Normal variant ejecuta y emite lifecycle evidence.
12. Replay policy se prueba.
13. Ningún fichero de core requiere modificación.

Segundo plugin: block step para probar BodyInvoker.
