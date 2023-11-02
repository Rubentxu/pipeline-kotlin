### Comandos


```bash
# Construir e instalar dependencias
$ gradle clean :pipeline-cli:shadowJar
```



```bash
# Ejecutar
$ java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-standalone.jar  -c pipeline-cli/src/test/resources/config.yaml -s pipeline-cli/src/test/resources/HelloWorld.pipeline.kts

```

```bash
# Ejecutar
$ java -cp pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-standalone.jar dev.rubentxu.pipeline.cli.PipelineCliKt -c pipeline-cli/src/test/resources/config.yaml -s pipeline-cli/src/test/resources/HelloWorld.pipeline.kts

```

```bash
# Ejecutar con print classpath
 gradle clean :pipeline-cli:printClasspath
```

```bash
$ pipeline-cli/build/native/nativeCompile/pipeline-cli -c pipeline-cli/src/test/resources/config.yaml -s pipeline-cli/src/test/resources/HelloWorld.pipeline.kts

```

```bash
# Ejecutar
$ kotlinc -script pipeline-cli/src/test/resources/HelloWorld.pipeline.kts -classpath pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-standalone.jar  

```