### Comandos


```bash
# Construir e instalar dependencias
$ gradle clean :pipeline-cli:shadowJar
```



```bash
# Ejecutar
$ java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT.jar -c pipeline-cli/src/test/resources/config.yaml -s pipeline-cli/src/test/resources/HelloWorld.pipeline.kts

```

```bash
# Ejecutar con print classpath
 gradle clean :pipeline-cli:printClasspath
``