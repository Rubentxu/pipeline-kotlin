### Introducción

Esta aplicación CLI permite ejecutar pipelines similares a los de Jenkinsfile pipeline DSL, pero con una sintaxis más sencilla y fácil de entender. La aplicación está diseñada para ser utilizada por desarrolladores, incluso aquellos con poca experiencia en pipelines.

### Compilación

Para compilar la aplicación CLI, siga estos pasos:

1. Abra una terminal o ventana de comandos.
2. Navegue hasta el directorio raíz del proyecto.
3. Ejecute el siguiente comando:

```bash
$ gradle clean :pipeline-cli:shadowJar
```

Este comando realiza dos tareas:

* **`gradle clean`:** Elimina el directorio de compilación, asegurando que no haya conflictos entre compilaciones antiguas y nuevas.
* **`:pipeline-cli:shadowJar`:** Compila el código fuente del proyecto en un archivo JAR (Java Archive) llamado "fat JAR" o "shadow JAR". Este archivo JAR contiene todas las dependencias necesarias para ejecutar la aplicación sin necesidad de instalarlas por separado.

### Ejecución

Una vez compilado el archivo JAR, puede ejecutarlo utilizando el siguiente comando:

```bash
java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/success.pipeline.kts
```

Este comando hace lo siguiente:

* **`java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar`:** Ejecuta el archivo JAR generado en el paso anterior.
* **`-c pipeline-cli/testData/config.yaml`:** Especifica la ubicación del archivo de configuración que define la pipeline que se va a ejecutar.
* **`-s pipeline-cli/testData/success.pipeline.kts`:** Especifica la ubicación del script Kotlin que implementa la lógica de la pipeline.

### Compilación nativa

La aplicación CLI también puede compilarse en una imagen nativa utilizando GraalVM native-image. Esto permite ejecutar la aplicación sin necesidad de una JVM (Java Virtual Machine).

Para compilar una imagen nativa, siga estos pasos:

1. Instale GraalVM.
2. Ejecute el siguiente comando:

```bash
$ native-image --class-path pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar \
  -H:Name=pipeline-kts dev.rubentxu.pipeline.cli.PipelineCliCommand
```

Este comando genera una imagen nativa llamada "pipeline-kts".

Para ejecutar la imagen nativa, ejecute el siguiente comando:

```bash
$ ./pipeline-kts -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/success.pipeline.kts
```

Este comando es similar al comando de ejecución normal, pero en lugar de usar `java -jar`, utiliza la imagen nativa generada.

### Ejecución con GraalVM native-image-agent

También puede ejecutar la aplicación CLI en la JVM y generar automáticamente los archivos de configuración necesarios para la compilación nativa utilizando la herramienta GraalVM native-image-agent.

Para hacerlo, ejecute el siguiente comando:

```bash
$ java -agentlib:native-image-agent=config-output-dir=pipeline-cli/build/libs -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/success.pipeline.kts
```

Este comando genera los archivos de configuración en el directorio `pipeline-cli/build/libs`.

### Ejecución con Kotlin

La aplicación CLI también se puede ejecutar utilizando el compilador de Kotlin `kotlinc`.

Para hacerlo, ejecute el siguiente comando:

```bash
$ kotlinc -script pipeline-cli/testData/success.pipeline.kts -classpath pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar 
```

Este comando ejecuta el script Kotlin especificado.

### Ejemplo de pipeline

El siguiente es un ejemplo de un script Kotlin que implementa una pipeline simple:

```kotlin
#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

pipeline {
    environment {
        "DISABLE_AUTH" += "true"
        "DB_ENGINE" += "sqlite"
    }
    stages {
        stage("Build") {
            steps {
                delay(1000) {
                    echo("Delay antes de ejecutar los pasos paralelos")
                }

                parallel(
                    "a" to Step {
                        delay(1000) {
                            echo("Delay This is branch a")
                        }

                    },
                    "b" to Step {
                        delay(300) {
                            echo("Delay This is branch b")
                        }
                    }
                )
                var stdOut = sh("pwd", returnStdout = true)
                echo(stdOut)
                var text = readFile("build.gradle.kts")
                echo(text)
                echo("Variable de entorno para DB_ENGINE es ${env["DB_ENGINE"]}")
            }
            post {
                always {
                    echo("This is the post section always in stage Test")
                }

                failure {
                    echo("This is the post section failure in stage Test")
                }
            }
        }
        stage("Test") {
            steps {
                sh("ls -la", returnStdout = true)
                retry(3) {
                    delay(3000) {
                        echo("Tests retry ....")
                        sh("ls -la .", returnStdout = true)
                    }

                }

                sh("ls -la /home", returnStdout = true)
            }

        }
    }
    post {
        always {
            echo("This is the post section always")
        }

        success {
            echo("This is the post section success")
        }

        failure {
            echo("This is the post section failure")
        }
    }
}
```

### Conceptos Claves

1. **Kotlin Scripting (Kotlin DSL):**
   Kotlin DSL (Domain Specific Language) permite escribir código Kotlin que es claro y conciso para definir configuraciones estructuradas,
   similar a cómo XML o YAML son usados en otras tecnologías. 
   Esto es especialmente útil en la definición de pipelines donde se prefieren configuraciones que son tanto legibles como mantenibles.

2. **Script Engine Manager:**
   En Kotlin, el `Script Engine Manager` gestiona diferentes tipos de scripts (incluyendo Kotlin scripts) que se pueden ejecutar. 
   Es útil en entornos donde se requiere cargar y ejecutar dinámicamente scripts basados en Kotlin, 
   permitiendo a los desarrolladores incorporar y evaluar código en tiempo de ejecución sin necesidad de recompilar la aplicación principal.

3. **Hosts:**
   En el contexto de los scripts, un "host" es el entorno que carga y ejecuta el script. Este puede proporcionar funciones adicionales como variables de entorno, métodos utilitarios (`echo`, `sh`, etc.), y manejo de errores, que el script puede utilizar directamente.

### Explicación del Pipeline Script

El script que has proporcionado define un pipeline de CI/CD usando Kotlin DSL. Este script incluye múltiples etapas y configuraciones que se ejecutan de manera secuencial o paralela dependiendo de la definición. Aquí te explico cada sección:

```kotlin
#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

pipeline {
    environment {
        "DISABLE_AUTH" += "true"
        "DB_ENGINE" += "sqlite"
    }
    ...
```

- **Shebang y Imports:**
    - `#!/usr/bin/env kotlin` indica que este script debe ejecutarse usando el intérprete de Kotlin.
    - Se importan módulos específicos que probablemente contienen definiciones DSL y extensiones que facilitan la creación del pipeline.

- **Entorno:**
  Se definen variables de entorno que estarán disponibles globalmente en todas las etapas del pipeline. 
  Estas pueden controlar el comportamiento de la aplicación, como deshabilitar la autenticación o definir el motor de base de datos.

```kotlin
stages {
    stage("Build") {
        steps {
            delay(1000) {
                echo("Delay antes de ejecutar los pasos paralelos")
            }
        ...
```

- **Etapas y Pasos:**
    - Define múltiples `stages` como "Build" y "Test". Cada `stage` contiene `steps` que se ejecutan en orden.
    - `delay(1000)` introduce una pausa de 1000 milisegundos antes de ejecutar el código dentro de su bloque, útil para sincronizar tareas.

```kotlin
            parallel(
                "a" to Step {
                    delay(1000) {
                        echo("Delay This is branch a")
                    }
                },
                "b" to Step {
                    delay(300) {
                        echo("Delay This is branch b")
                    }
                }
            )
```

- **Paralelismo:**
    - `parallel` permite ejecutar múltiples `Step` simultáneamente. En este caso, las ramas "a" y "b" se ejecutan en paralelo, cada una con sus propios delays y mensajes.

```kotlin
        post {
            always {
                echo("This is the post section always in stage Test")
            }
        ...
```

- **Sección Post:**
    - Dentro de cada etapa, se pueden definir acciones `post` que se ejecutan siempre (`always`), 
    - en caso de éxito (`success`), o fallo (`failure`). Esto es útil para limpieza o notificaciones finales.

### Utilidad en CI/CD

Este script es un ejemplo de cómo Kotlin DSL puede ser utilizado para definir procesos complejos de integración 
y despliegue continuo de una forma que es altamente legible y fácil de mantener. 
Permite a los desarrolladores especificar detalladamente cómo construir, probar y manejar post-procesos dependiendo del 
resultado de cada etapa, todo dentro del mismo script.

En resumen, Kotlin Scripting y su configuración de engine manager facilitan la ejecución dinámica y adaptable de scripts, 
mientras que los hosts proporcionan un entorno rico en funcionalidades para ejecutar esos scripts, 
haciéndolos extremamente poderosos para automatizar y gestionar pipelines de CI/CD



---


### Introduction

This CLI application allows executing pipelines similar to those of the Jenkinsfile pipeline DSL, but with a simpler and easier-to-understand syntax. The application is designed to be used by developers, including those with little experience in pipelines.

### Compilation

To compile the CLI application, follow these steps:

1. Open a terminal or command window.
2. Navigate to the root directory of the project.
3. Execute the following command:

```bash
$ gradle clean :pipeline-cli:shadowJar
```

This command performs two tasks:

* **`gradle clean`:** Removes the build directory, ensuring there are no conflicts between old and new builds.
* **`:pipeline-cli:shadowJar`:** Compiles the project's source code into a JAR file (Java Archive) called "fat JAR" or "shadow JAR". This JAR file contains all the necessary dependencies to run the application without needing to install them separately.

### Execution

Once the JAR file is compiled, you can run it using the following command:

```bash
java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/success.pipeline.kts
```

This command does the following:

* **`java -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar`:** Executes the JAR file generated in the previous step.
* **`-c pipeline-cli/testData/config.yaml`:** Specifies the location of the configuration file that defines the pipeline to be executed.
* **`-s pipeline-cli/testData/success.pipeline.kts`:** Specifies the location of the Kotlin script that implements the logic of the pipeline.

### Native Compilation

The CLI application can also be compiled into a native image using GraalVM native-image. This allows the application to run without the need for a JVM (Java Virtual Machine).

To compile a native image, follow these steps:

1. Install GraalVM.
2. Execute the following command:

```bash
$ native-image --class-path pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar \
  -H:Name=pipeline-kts dev.rubentxu.pipeline.cli.PipelineCliCommand
```

This command generates a native image called "pipeline-kts".

To execute the native image, run the following command:

```bash
$ ./pipeline-kts -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/success.pipeline.kts
```

This command is similar to the normal execution command, but instead of using `java -jar`, it uses the generated native image.

### Execution with GraalVM native-image-agent

You can also run the CLI application on the JVM and automatically generate the necessary configuration files for native compilation using the GraalVM native-image-agent tool.

To do this, execute the following command:

```bash
$ java -agentlib:native-image-agent=config-output-dir=pipeline-cli/build/libs -jar pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar -c pipeline-cli/testData/config.yaml -s pipeline-cli/testData/success.pipeline.kts
```

This command generates the configuration files in the directory `pipeline-cli/build/libs`.

### Execution with Kotlin

The CLI application can also be run using the Kotlin compiler `kotlinc`.

To do this, execute the following command:

```bash
$ kotlinc -script pipeline-cli/testData/success.pipeline.kts -classpath pipeline-cli/build/libs/pipeline-cli-1.0-SNAPSHOT-all.jar 
```

This command executes the specified Kotlin script.

### Example of a Pipeline

Here is an example of a Kotlin script that implements a simple pipeline:

```kotlin
#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

pipeline {
    environment {
        "DISABLE_AUTH" += "true"
        "DB_ENGINE" += "sqlite"
    }
    stages {
        stage("Build") {
            steps {
                delay(1000) {
                    echo("Delay before executing the parallel steps")
                }

                parallel(
                    "a" to Step {
                        delay(1000) {
                            echo("Delay This is branch a")
                        }

                    },
                    "b" to Step {
                        delay(300) {
                            echo("Delay This is branch b")
                        }
                    }
                )
                var stdOut = sh("pwd", returnStdout = true)
                echo(stdOut)
                var text = readFile("build.gradle.kts")
                echo(text)
                echo("Environment variable for DB_ENGINE is ${env["DB_ENGINE"]}")
            }
            post {
                always {
                    echo("This is the post section always in stage Test")
                }

                failure {
                    echo("This is the post section failure in stage Test")
                }
            }
        }
        stage("Test") {
            steps {
                sh("ls -la", returnStd

out = true)
                retry(3) {
                    delay(3000) {
                        echo("Tests retry ....")
                        sh("ls -la .", returnStdout = true)
                    }

                }

                sh("ls -la /home", returnStdout = true)
            }

        }
    }
    post {
        always {
            echo("This is the post section always")
        }

        success {
            echo("This is the post section success")
        }

        failure {
            echo("This is the post section failure")
        }
    }
}
```

### Key Concepts

1. **Kotlin Scripting (Kotlin DSL):**
   Kotlin DSL (Domain Specific Language) allows you to write clear and concise Kotlin code to define structured configurations,
   similar to how XML or YAML are used in other technologies.
   This is particularly useful in defining pipelines where configurations that are both readable and maintainable are preferred.

2. **Script Engine Manager:**
   In Kotlin, the `Script Engine Manager` manages different types of scripts (including Kotlin scripts) that can be executed.
   It is useful in environments where dynamic loading and execution of Kotlin-based scripts are required,
   allowing developers to incorporate and evaluate code in real-time without the need to recompile the main application.

3. **Hosts:**
   In the context of the scripts, a "host" is the environment that loads and executes the script. This can provide additional functions such as environment variables, utility methods (`echo`, `sh`, etc.), and error handling, which the script can use directly.

### Explanation of the Pipeline Script

The script you provided defines a CI/CD pipeline using Kotlin DSL. This script includes multiple stages and configurations that are executed either sequentially or in parallel, depending on the definition. Here I explain each section:

```kotlin
#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

pipeline {
    environment {
        "DISABLE_AUTH" += "true"
        "DB_ENGINE" += "sqlite"
    }
    ...
```

- **Shebang and Imports:**
    - `#!/usr/bin/env kotlin` indicates that this script should be executed using the Kotlin interpreter.
    - Specific modules are imported, likely containing DSL definitions and extensions that facilitate the creation of the pipeline.

- **Environment:**
  Environment variables are defined that will be available globally across all stages of the pipeline.
  These can control the application's behavior, such as disabling authentication or defining the database engine.

```kotlin
stages {
    stage("Build") {
        steps {
            delay(1000) {
                echo("Delay before executing the parallel steps")
            }
        ...
```

- **Stages and Steps:**
    - Defines multiple `stages` such as "Build" and "Test". Each `stage` contains `steps` that are executed in order.
    - `delay(1000)` introduces a pause of 1000 milliseconds before executing the code within its block, useful for synchronizing tasks.

```kotlin
            parallel(
                "a" to Step {
                    delay(1000) {
                        echo("Delay This is branch a")
                    }
                },
                "b" to Step {
                    delay(300) {
                        echo("Delay This is branch b")
                    }
                }
            )
```

- **Parallelism:**
    - `parallel` allows executing multiple `Step` simultaneously. In this case, branches "a" and "b" are executed in parallel, each with their own delays and messages.

```kotlin
        post {
            always {
                echo("This is the post section always in stage Test")
            }
        ...
```

- **Post Section:**
    - Within each stage, actions `post` that are executed always (`always`),
    - in case of success (`success`), or failure (`failure`). This is useful for cleanup or final notifications.

### Utility in CI/CD

This script is an example of how Kotlin DSL can be used to define complex processes of integration
and continuous deployment in a way that is highly readable and easy to maintain.
It allows developers to specify in detail how to build, test, and handle post-processes depending on the
outcome of each stage, all within the same script.

In summary, Kotlin Scripting and its engine manager configuration facilitate the dynamic and adaptable execution of scripts,
while the hosts provide a feature-rich environment to execute those scripts,
making them extremely powerful for automating and managing CI/CD pipelines.