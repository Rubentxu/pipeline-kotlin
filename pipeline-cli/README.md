### Comandos


```bash
# Construir e instalar dependencias
$ gradle clean shadowJar
```


```bash
# Ejecutar
$ java -jar build/libs/pipeline-cli-1.0-SNAPSHOT.jar -c ../examples/simple/config.yaml -s ../examples/simple/HelloWorld.kts

```