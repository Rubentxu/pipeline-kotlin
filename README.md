### Comandos

#### Compilar

```bash
$ gradle clean :pipeline-cli:shadowJar
```
Este comando realiza dos tareas. Primero, `gradle clean` se utiliza para eliminar el directorio de compilación, lo que significa que elimina todos los archivos compilados previamente. Esto se hace para asegurarse de que no haya conflictos entre las compilaciones antiguas y nuevas.

Después, `:pipeline-cli:shadowJar` es una tarea de Gradle que compila el código fuente del proyecto en un archivo JAR (Java Archive). Este archivo JAR, conocido como "fat JAR" o "shadow JAR", incluirá todas las dependencias necesarias, por lo que se puede ejecutar de forma independiente sin necesidad de instalar ninguna dependencia adicional. Un "fat JAR" es un archivo JAR que contiene tanto las clases de la aplicación como todas sus dependencias en un solo archivo.

---
#### Compilación nativa
Es importante tener en cuenta que native-image es muy sensible a la reflexión y a la carga dinámica de clases. 
Si tu aplicación utiliza estas características, es posible que necesites proporcionar configuraciones adicionales para 
que native-image pueda analizar y compilar adecuadamente tu aplicación. 
Esto se hace a menudo a través de archivos de configuración JSON que especifican qué clases y métodos deben ser considerados para la reflexión. Puedes generar estos archivos automáticamente durante la ejecución de tu aplicación en una JVM normal utilizando el agente de native-image:

```bash
java  -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar
```

```bash
$  native-image --class-path pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar \
  -H:IncludeResources='.*\.properties|templates/.*\.tpl|META-INF/services/.*' \
  --initialize-at-build-time=javax.script.ScriptEngineFactory \
  -H:Name=pipeline-kts dev.rubentxu.pipeline.cli.PipelineCliCommand
```
Este comando utiliza la herramienta native-image de GraalVM para compilar el código en una imagen nativa. Aunque la imagen nativa normalmente puede ejecutarse directamente sin necesidad de una JVM (Java Virtual Machine), en este caso, se está generando una imagen de "fallback".

La advertencia que se mostrara en la compilación, "Warning: Image 'pipeline-kts' is a fallback image that requires a JDK for execution", indica que la imagen nativa generada requerirá una JDK (Java Development Kit) para su ejecución. Esto puede suceder cuando native-image no puede analizar completamente el código para crear una imagen nativa completamente independiente. En tales casos, genera una imagen de "fallback" que aún necesita una JVM para ejecutarse.

El argumento `--class-path` especifica la ruta al archivo JAR que se va a compilar. `-H:Name=pipeline-kts` establece el nombre de la imagen nativa resultante. Finalmente, `dev.rubentxu.pipeline.cli.PipelineCliCommand` es la clase principal que se ejecutará cuando se inicie la imagen nativa.

```bash
native-image \
  --class-path pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar \
  -H:Name=pipeline-kts \
  -H:IncludeResources='.*\.properties|templates/.*\.tpl|META-INF/services/.*' \
  --initialize-at-build-time=javax.script.ScriptEngineFactory \
  -H:ReflectionConfigurationFiles=pipeline-cli/build/libs/reflect-config.json \
  -H:DynamicProxyConfigurationFiles=pipeline-cli/build/libs/proxy-config.json \
  -H:ResourceConfigurationFiles=pipeline-cli/build/libs/resource-config.json \
  dev.rubentxu.pipeline.cli.PipelineCliCommand
```
Este es un ejemplo de un comando `native-image` que utiliza archivos de configuración JSON para especificar qué clases y
métodos deben ser considerados para la reflexión, la carga dinámica de clases y la configuración de recursos. 
Los archivos de configuración JSON se pueden generar automáticamente durante la ejecución de la aplicación en una JVM normal
utilizando el agente de native-image.


---
#### Ejecutar

```bash
$ java -agentlib:native-image-agent=config-output-dir=pipeline-cli/build/libs -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar  -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/HelloWorld.pipeline.kts
```
Este comando ejecuta el archivo JAR generado utilizando el comando `java -jar`. Los argumentos `-c` y `-s` 
son probablemente opciones específicas de la aplicación, que especifican la ubicación de un archivo de configuración y 
un script, respectivamente.
GraalVM también proporciona la herramienta native-image-agent, que puedes usar para ejecutar tu aplicación en la JVM y 
generar automáticamente estos archivos de configuración. 
Esto se puede hacer agregando el argumento `-agentlib:native-image-agent=config-output-dir=<output-dir>` al comando `java`:

```bash
---

```bash
$ java -cp pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar dev.rubentxu.pipeline.cli.PipelineCliCommand -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/HelloWorld.pipeline.kts
```
Este comando es similar al anterior, pero en lugar de usar `-jar`, usa `-cp` (classpath) para especificar el archivo JAR y luego proporciona la clase principal que se debe ejecutar. Los argumentos `-c` y `-s` funcionan de la misma manera que en el comando anterior.

---

```bash
$ ./pipeline-kts -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/HelloWorld.pipeline.kts
```
Este comando ejecuta la imagen nativa generada por el comando `native-image`. Nuevamente, los argumentos `-c` y `-s` especifican la ubicación de un archivo de configuración y un script, respectivamente.

---

```bash
$ kotlinc -script pipeline-cli/testData/HelloWorld.pipeline.kts -classpath pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar  
```
Este comando utiliza el compilador de Kotlin `kotlinc` para ejecutar un script de Kotlin. El argumento `-script` especifica la ubicación del script que se va a ejecutar, y `-classpath` especifica la ruta al archivo JAR que contiene las clases y recursos necesarios para ejecutar el script.


---

---

### Commands

#### Compile

```bash
$ gradle clean :pipeline-cli:shadowJar
```
This command performs two tasks. First, `gradle clean` is used to remove the build directory, which means it deletes all previously compiled files. This is done to ensure there are no conflicts between old and new builds.

Next, `:pipeline-cli:shadowJar` is a Gradle task that compiles the project's source code into a JAR (Java Archive) file. This JAR file, known as a "fat JAR" or "shadow JAR", will include all necessary dependencies, so it can be run independently without needing to install any additional dependencies. A "fat JAR" is a JAR file that contains both the application's classes and all its dependencies in a single file.

---
#### Native Compilation

```bash
$  native-image --class-path pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar -H:Name=pipeline-kts dev.rubentxu.pipeline.cli.PipelineCliCommand
```
This command uses the GraalVM's native-image tool to compile the code into a native image. Although the native image can usually be run directly without needing a JVM (Java Virtual Machine), in this case, a "fallback" image is being generated.

The warning that will be shown during the compilation, "Warning: Image 'pipeline-kts' is a fallback image that requires a JDK for execution", indicates that the generated native image will require a JDK (Java Development Kit) for its execution. This can happen when native-image cannot fully analyze the code to create a completely independent native image. In such cases, it generates a "fallback" image that still needs a JVM to run.

The `--class-path` argument specifies the path to the JAR file to be compiled. `-H:Name=pipeline-kts` sets the name of the resulting native image. Finally, `dev.rubentxu.pipeline.cli.PipelineCliCommand` is the main class that will be run when the native image is started.

---
#### Run

```bash
$ java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar  -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/HelloWorld.pipeline.kts
```
This command runs the generated JAR file using the `java -jar` command. The `-c` and `-s` arguments are likely application-specific options, specifying the location of a configuration file and a script, respectively.

---

```bash
$ java -cp pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar dev.rubentxu.pipeline.cli.PipelineCliCommand -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/HelloWorld.pipeline.kts
```
This command is similar to the previous one, but instead of using `-jar`, it uses `-cp` (classpath) to specify the JAR file and then provides the main class to be run. The `-c` and `-s` arguments work in the same way as in the previous command.

---

```bash
$ ./pipeline-kts -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/HelloWorld.pipeline.kts
```
```bash
$ ./pipeline-kts -c pipeline-cli/src/test/resources/config.yaml -s pipeline-cli/src/test/resources/HelloWorld.pipeline.kts
```
This command runs the native image generated by the `native-image` command. Again, the `-c` and `-s` arguments specify the location of a configuration file and a script, respectively.

---

```bash
$ kotlinc -script pipeline-cli/testData/HelloWorld.pipeline.kts -classpath pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar  
```
This command uses the Kotlin compiler `kotlinc` to run a Kotlin script. The `-script` argument specifies the location of the script to be run, and `-classpath` specifies the path to the JAR file containing the classes and resources necessary to run the script.