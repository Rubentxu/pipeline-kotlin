# Pipeline Kotlin

[![Estado de Compilación](https://img.shields.io/badge/build-passing-brightgreen.svg)](https://github.com/rubentxu/pipeline-kotlin)
[![Cobertura de Pruebas](https://img.shields.io/badge/coverage-100%25-brightgreen.svg)](https://github.com/rubentxu/pipeline-kotlin)
[![Licencia: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0.0-blue.svg)](http://kotlinlang.org)

Un motor CI/CD moderno y con tipado seguro construido con Kotlin DSL, ofreciendo una alternativa poderosa a Jenkins/Groovy y sistemas de pipelines basados en YAML.

## Tabla de Contenidos

- [Características](#características)
- [Instalación](#instalación)
- [Inicio Rápido](#inicio-rápido)
- [Uso](#uso)
- [Arquitectura](#arquitectura)
- [Estructura del Proyecto](#estructura-del-proyecto)
- [Pruebas](#pruebas)
- [Contribuyendo](#contribuyendo)
- [Licencia](#licencia)
- [Agradecimientos](#agradecimientos)

## Características

- **🚀 DSL con Tipado Seguro en Kotlin**: Escribe configuraciones de pipeline con soporte completo del IDE, autocompletado y validación en tiempo de compilación
- **🔒 Seguridad Avanzada**: Modelo de sandbox dual usando GraalVM Isolates para ejecución de scripts y contenedores para aislamiento de tareas
- **📦 Arquitectura Modular**: Separación limpia de responsabilidades con múltiples módulos (core, CLI, backend, config)
- **🔌 Sistema de Plugins Extensible**: Basado en `java.util.ServiceLoader` con resolución de dependencias Maven
- **🎯 100% de Cobertura de Pruebas**: Suite de pruebas completa asegurando confiabilidad y mantenibilidad
- **🐳 Contenedores Primero**: Soporte nativo de Docker para ejecución de tareas basadas en agentes
- **⚡ Alto Rendimiento**: Ejecución asíncrona con corrutinas de Kotlin
- **🛡️ Gestión de Recursos**: Límites de CPU, memoria y tiempo de ejecución con monitoreo detallado
- **🏎️ Compilación Nativa**: Soporte para imágenes nativas de GraalVM con arranque instantáneo y menor uso de memoria

## Instalación

### Prerrequisitos

- JDK 21 o superior
- Gradle 8.4 o superior
- Docker (para agentes basados en contenedores)
- **GraalVM CE 21+ (recomendado para compilación nativa)**

#### Instalando GraalVM

```bash
# Usando SDKMAN (recomendado)
sdk install java 21.0.2-graalce
sdk use java 21.0.2-graalce

# Verificar instalación
java -version
native-image --version
```

### Compilar desde el Código Fuente

```bash
git clone https://github.com/rubentxu/pipeline-kotlin.git
cd pipeline-kotlin
gradle build
```

### Compilar el CLI

Para compilar la aplicación CLI:

```bash
gradle clean :pipeline-cli:shadowJar
```

Esto crea un JAR completo con todas las dependencias en `pipeline-cli/build/libs/pipeline-cli-0.1.0-all.jar`.

## Inicio Rápido

### Ejecutar la distribución

PipelineK se distribuye como un ZIP con su lanzador `bin/pipelinek`; no
como un JAR ejecutable con `java -jar`. La versión publicada se descarga
del Release de GitHub y se ejecuta directamente:

```bash
unzip pipelinek-<VERSION>.zip
./pipelinek-<VERSION>/bin/pipelinek version
./pipelinek-<VERSION>/bin/pipelinek doctor     # jdk / os / workdir / escritura
```

Si clonaste el repositorio, la misma distribución se construye en local:

```bash
cd v2 && ./gradlew :pipeline-application:installDist
cd ..
./v2/pipeline-application/build/install/pipelinek/bin/pipelinek version
```

### Ejecutar un pipeline

```bash
pipelinek validate mi-pipeline.pipeline.kts
pipelinek run mi-pipeline.pipeline.kts
```

Para partir el arranque y el estado sin tocar nada del entorno:

```bash
pipelinek run --isolated --control-root /tmp/mi-corrida mi-pipeline.pipeline.kts
```

Los escenarios completos y ejecutables están en `v2/examples/`, y
`v2/examples/run.sh` los recorre todos; su catálogo está en la sección
Examples del [README en inglés](README.md#examples).

## Uso

### Estructura del Pipeline

Crea un script de pipeline (`example.pipeline.kts`):

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
                echo("Iniciando compilación...")
                sh("gradle build")
                
                // Ejecución paralela
                parallel(
                    "Pruebas Unitarias" to Step {
                        sh("gradle test")
                    },
                    "Pruebas de Integración" to Step {
                        sh("gradle integrationTest")
                    }
                )
            }
            post {
                always {
                    echo("Etapa de compilación completada")
                }
                failure {
                    echo("¡La compilación falló!")
                }
            }
        }
        
        stage("Deploy") {
            steps {
                retry(3) {
                    sh("kubectl apply -f k8s/")
                }
            }
        }
    }
    
    post {
        success {
            echo("¡Pipeline completado exitosamente!")
        }
        failure {
            echo("¡El pipeline falló!")
        }
    }
}
```

### Características Clave del DSL

#### Variables de Entorno
```kotlin
environment {
    "KEY" += "value"
    "PATH" += "${env["PATH"]}:/custom/path"
}
```

#### Ejecución Paralela
```kotlin
parallel(
    "tarea1" to Step { /* ... */ },
    "tarea2" to Step { /* ... */ },
    "tarea3" to Step { /* ... */ }
)
```

#### Manejo de Errores
```kotlin
retry(3) {
    // Código que podría fallar
}

delay(1000) {
    // Código a ejecutar después del retraso
}
```

#### Pasos Integrados
- `echo(message)`: Imprimir mensajes
- `sh(command, returnStdout)`: Ejecutar comandos shell
- `readFile(path)`: Leer contenido de archivos
- `writeFile(path, content)`: Escribir en archivos
- `delay(ms) { }`: Retrasar ejecución
- `retry(times) { }`: Reintentar en caso de fallo

## Arquitectura

### Soporte Multi-DSL

El sistema soporta múltiples motores DSL a través de una interfaz unificada:

```kotlin
interface DslEngine<TResult : Any> {
    val engineId: String
    val supportedExtensions: Set<String>
    
    suspend fun compile(scriptFile: File, context: DslCompilationContext): DslCompilationResult<TResult>
    suspend fun execute(compiledScript: CompiledScript, context: DslExecutionContext): DslExecutionResult<TResult>
    suspend fun validate(scriptContent: String, context: DslCompilationContext): DslValidationResult
}
```

### Componentes Principales

1. **Gestor DSL**: Orquestador central para todas las operaciones DSL
2. **Motor de Ejecución**: Gestiona la ejecución del pipeline con límites de recursos
3. **Gestor de Seguridad**: Implementa modelo de sandbox dual
4. **Sistema de Plugins**: Carga dinámica de extensiones
5. **Monitor de Recursos**: Rastrea CPU, memoria y tiempo de ejecución

### Modelo de Seguridad

- **Sandbox de Script**: GraalVM Isolates con políticas restringidas
- **Sandbox de Tareas**: Aislamiento basado en contenedores para comandos
- **Límites de Recursos**: Restricciones configurables de CPU, memoria y tiempo
- **Sistema de Permisos**: Control de acceso de grano fino

## Estructura del Proyecto

```
pipeline-kotlin/
├── core/                    # Motor principal, DSL y componentes de seguridad
├── pipeline-cli/           # Interfaz de línea de comandos
├── pipeline-backend/       # API REST y servicios web
├── pipeline-config/        # Gestión de configuración
├── lib-examples/           # Ejemplos de bibliotecas y plugins
├── gradle/                 # Configuración de compilación
├── libs.versions.toml      # Versiones centralizadas de dependencias
└── docs/                   # Documentación
```

## Pruebas

Esta sección afirmaba antes «100% de cobertura con 265 pruebas
exitosas» y daba comandos contra `gradle test`, `jacocoTestReport` y
`:core:test`. Ninguna de las tres cosas era cierta: no existe el módulo
`core`, la herramienta de cobertura es Kover y no Jacoco, y la suite
heredada se borró entera el 2026-10-18 (RESET de testing). El número no
se sustituye por otro porque no hay ninguno que anunciar todavía: la
verificación permanente vive hoy en `scripts/verify-all.sh`.

### La puerta de verificación

```bash
scripts/verify-all.sh              # la puerta completa
scripts/verify-all.sh --no-budget  # omite solo el techo de LOC
```

Ejecuta, en este orden y-parando en el primer stage que falle:

1. `detekt`, `apiCheck` y la distribución local (`installDist`).
2. Los tests unitarios de cada módulo que tenga `src/test`, leyendo el
   resultado del XML de JUnit y no el código de salida de Gradle.
3. Los escenarios E2E de `v2/examples/`, vía `v2/examples/run.sh`.
4. El techo de LOC (`scripts/check-budgets.py`).
5. El techo de reloj, 900 s por AGENTS.md.

### Qué existe y qué no

| Capa | Estado |
|---|---|
| Análisis estático y compatibilidad de API | Activa: `detekt`, `apiCheck` |
| E2E sobre la distribución real | Activa: 35 escenarios en `v2/examples/` |
| Tests unitarios Kotlin | carril `src/test` reservado y cableado; se rellena por fases |
| Carriles de prueba bespoke (`pluginContractTest`, `installedContractTest`) | eliminados el 2026-10-18 |

Un escenario E2E marcado `NOT_RUN` —hoy `security/14-credentials-bindings`,
que necesita un almacén de credenciales sembrado por TTY— no es un verde:
es el tercer estado honesto, y `scripts/verify-all.sh` lo cuenta aparte.

## Contribuyendo

¡Damos la bienvenida a las contribuciones! Por favor sigue estas pautas:

### Flujo de Trabajo de Desarrollo

1. Haz un fork del repositorio
2. Crea una rama de característica (`git checkout -b feature/caracteristica-asombrosa`)
3. Realiza tus cambios siguiendo prácticas TDD
4. Asegúrate de que todas las pruebas pasen
5. Haz commit usando commits convencionales
6. Abre un Pull Request

### Convención de Commits

Seguimos [Commits Convencionales](https://www.conventionalcommits.org/):

- `feat:` Nuevas características
- `fix:` Corrección de errores
- `docs:` Cambios en documentación
- `test:` Adiciones o modificaciones de pruebas
- `refactor:` Refactorización de código
- `chore:` Tareas de mantenimiento

### Estándares de Código

- Seguir las convenciones de codificación de Kotlin
- Aplicar principios SOLID y prácticas de Clean Code
- Mantener patrones de arquitectura hexagonal
- Agregar pruebas completas para nuevas características
- Actualizar la documentación según sea necesario

## Licencia

Este proyecto está licenciado bajo la Licencia MIT:

```
MIT License

Copyright (c) 2025 Rubén Torres

Se concede permiso, libre de cargos, a cualquier persona que obtenga una copia
de este software y de los archivos de documentación asociados (el "Software"),
a utilizar el Software sin restricción, incluyendo sin limitación los derechos
a usar, copiar, modificar, fusionar, publicar, distribuir, sublicenciar, y/o
vender copias del Software, y a permitir a las personas a las que se les
proporcione el Software a hacer lo mismo, sujeto a las siguientes condiciones:

El aviso de copyright anterior y este aviso de permiso se incluirán en todas
las copias o partes sustanciales del Software.

EL SOFTWARE SE PROPORCIONA "COMO ESTÁ", SIN GARANTÍA DE NINGÚN TIPO, EXPRESA O
IMPLÍCITA, INCLUYENDO PERO NO LIMITADO A GARANTÍAS DE COMERCIALIZACIÓN,
IDONEIDAD PARA UN PROPÓSITO PARTICULAR E INCUMPLIMIENTO. EN NINGÚN CASO LOS
AUTORES O PROPIETARIOS DE LOS DERECHOS DE AUTOR SERÁN RESPONSABLES DE NINGUNA
RECLAMACIÓN, DAÑOS U OTRAS RESPONSABILIDADES, YA SEA EN UNA ACCIÓN DE CONTRATO,
AGRAVIO O CUALQUIER OTRO MOTIVO, DERIVADAS DE, FUERA DE O EN CONEXIÓN CON EL
SOFTWARE O EL USO U OTRO TIPO DE ACCIONES EN EL SOFTWARE.
```

## Agradecimientos

- Gracias al equipo de Kotlin por crear un lenguaje increíble
- Inspirado por Jenkins Pipeline y GitHub Actions
- Construido con ❤️ usando:
  - [Kotlin](https://kotlinlang.org/) - Lenguaje de programación
  - [Gradle](https://gradle.org/) - Automatización de compilación
  - [Kotest](https://kotest.io/) - Framework de pruebas
  - [MockK](https://mockk.io/) - Biblioteca de mocking
  - [GraalVM](https://www.graalvm.org/) - JVM avanzada para características de seguridad
  - [Docker](https://www.docker.com/) - Plataforma de contenedores

---

**Nota**: Este proyecto está en desarrollo activo. Para preguntas o problemas, por favor visita nuestro [repositorio de GitHub](https://github.com/rubentxu/pipeline-kotlin).