### **Definición de Requisitos**

**Asunto:** Documentación de Proyecto para un Motor de CI/CD en Kotlin.
**Objetivo:** Interpretar los dos documentos delimitados a continuación: una Propuesta de Proyecto (PRP) y un Documento de Requisitos de Producto (PRD).
**Formato:**

- Cada documento está encapsulado en bloques `--- DOCUMENT START: [TIPO] ---` y `--- DOCUMENT END: [TIPO] ---`.
- Cada documento comienza con un bloque de metadatos en formato YAML para un resumen contextual rápido.
- Los requisitos en el PRD utilizan identificadores jerárquicos (p. ej., `FR-1.1`).
- El lenguaje de los requisitos se adhiere a las palabras clave de RFC 2119 (`MUST`, `SHOULD`, `MAY`) para eliminar la ambigüedad.
- Los pasos de compatibilidad en el PRD (`FR-6`) incluyen un `id` único para una referencia inequívoca.

---

\--- DOCUMENT START: PRP ---

```yaml
# METADATA: Project Request Proposal
document_type: "Project Request Proposal (PRP)"
project_name: "Kotlin Pipeline DSL"
version: 1.1
status: "Proposed"
date: "2025-07-05"
summary: "Propuesta para desarrollar un motor de CI/CD moderno, seguro y extensible basado en un DSL de Kotlin, como alternativa a Jenkins/Groovy y soluciones basadas en YAML."
```

### **Propuesta de Solicitud de Proyecto (PRP): Kotlin Pipeline DSL**

#### **1. Resumen Ejecutivo**

Se propone el desarrollo de un motor de scripting de CI/CD, "Kotlin Pipeline DSL". El objetivo es dotar a los desarrolladores de una herramienta para definir pipelines como código (`Configuration as Code`) que sea simultáneamente potente, segura y productiva. La solución se fundamenta en un DSL declarativo basado en Kotlin, ofreciendo una alternativa superior a las limitaciones de Jenkins (curva de aprendizaje, verbosidad) y los sistemas YAML (falta de tipado, complejidad oculta). Los diferenciadores clave son la seguridad mediante sandboxing con **Oracle GraalVM Isolates** y **contenedores**, la productividad a través del ecosistema de Kotlin (tipado estático, soporte de IDE), y una arquitectura de plugins extensible.

#### **2. Caso de Negocio y Problema a Resolver**

El mercado de CI/CD actual obliga a los equipos a elegir entre la simplicidad frágil de YAML y el poder complejo de Groovy/Jenkins. Esta elección resulta en pipelines difíciles de mantener, propensos a errores en tiempo de ejecución y que suponen un riesgo de seguridad. Este proyecto aborda directamente esta ineficiencia, con el objetivo de reducir los errores de configuración en un 40% y aumentar la productividad del desarrollador en un 25% al permitirles usar un lenguaje moderno y seguro.

#### **3. Solución Propuesta**

La solución consiste en un motor de ejecución que **MUST** parsear y ejecutar ficheros de script con la extensión `.pipeline.kts`. La arquitectura de la solución **MUST** incluir:

- **Un DSL en Kotlin:** Replicará la sintaxis declarativa e intuitiva de Jenkins (`pipeline`, `stages`, `agent`).
- **Un Motor de Orquestación:** Componente central que gestiona el ciclo de vida del pipeline.
- **Un Modelo de Sandboxing Dual:**
  1. **Sandbox de Script:** La lógica del pipeline **MUST** ejecutarse dentro de un GraalVM Isolate con políticas de seguridad restrictivas (CPU, memoria, acceso a red/ficheros).
  2. **Sandbox de Tareas:** Los comandos de usuario (`sh`) **MUST** ejecutarse en un entorno de aislamiento fuerte, definido por un `agent`, siendo un contenedor Docker el agente primario.
- **Un Sistema de Plugins:** Basado en `java.util.ServiceLoader` para el descubrimiento y `Apache Maven Resolver` para la gestión de dependencias externas.

#### **4. Alcance del Proyecto**

##### **Funcionalidades INCLUIDAS en el MVP (Producto Mínimo Viable):**

- **DSL Core:** Directivas `pipeline`, `agent` (solo tipo `docker`), `stages`, `stage`, `steps`.
- **Pasos Esenciales:** `sh`, `echo`, `checkout`.
- **Motor de Ejecución Básico:** Ejecución secuencial de etapas.
- **Sandboxing de Agente:** Aislamiento de `sh` vía Docker.
- **Fundamentos del Sistema de Plugins:** API `Step` y descubrimiento vía `ServiceLoader`.

##### **Funcionalidades EXCLUIDAS del MVP (Trabajo Futuro):**

- Directivas avanzadas (`post`, `when`, `matrix`, `parallel`).
- Soporte para agentes no-Docker (`kubernetes`, `local`).
- La biblioteca de compatibilidad completa de Jenkins (`FR-6` del PRD).
- Interfaces de usuario.

#### **5. Pila Tecnológica**


| Componente              | Tecnología               | Justificación                                                          |
| :---------------------- | :------------------------ | :---------------------------------------------------------------------- |
| **Lenguaje DSL**        | Kotlin                    | Tipado estático, expresividad, ecosistema JVM, soporte de IDE.         |
| **Motor Scripting**     | Kotlin Scripting Host API | API oficial para scripting.                                             |
| **Sandbox de Script**   | Oracle GraalVM Isolates   | **CRÍTICO:** Reemplazo moderno y seguro del `SecurityManager`.         |
| **Sandbox de Tareas**   | Contenedores (Docker)     | Aislamiento máximo para código de usuario, estándar de la industria. |
| **Gestor Dependencias** | Apache Maven Resolver     | API estándar para resolución de artefactos.                           |

#### **6. Análisis de Riesgos y Plan de Mitigación**


| Riesgo                                    | Probabilidad | Impacto | Mitigación                                                                                                |
| :---------------------------------------- | :----------- | :------ | :--------------------------------------------------------------------------------------------------------- |
| Volatilidad de la API de Kotlin Scripting | Media        | Medio   | Crear una capa de abstracción interna para aislar el motor de la API.                                     |
| Complejidad del Sandbox de GraalVM        | Media        | Alto    | Priorizar el sandbox de contenedores en el MVP; implementar GraalVM con políticas "deny-all" por defecto. |
| Dependencias y Plugins maliciosos         | Alta         | Alto    | Requerir checksums (SHA-256) y firmas PGP opcionales para plugins.                                         |

#### **7. Hoja de Ruta**

- **M0 (2 semanas):** Aprobación del PRP, configuración de CI.
- **M1 (4 semanas):** Desarrollo del DSL y compilador.
- **M2 (4 semanas):** Motor de ejecución básico, agente Docker, pasos MVP.
- **M3 (3 semanas):** SDK de plugins y descubrimiento.
- **M4 (3 semanas):** Integración del gestor de dependencias.
- **M5 (2 semanas):** Lanzamiento de la Versión MVP (Beta Pública).

\--- DOCUMENT END: PRP ---

---

\--- DOCUMENT START: PRD ---

```yaml
# METADATA: Product Requirements Document
document_type: "Product Requirements Document (PRD)"
product_name: "Kotlin Pipeline DSL"
version: 1.1
status: "Draft"
date: "2025-07-05"
summary: "Especificación detallada de los requisitos funcionales y no funcionales para la versión MVP y la hoja de ruta de compatibilidad del motor Kotlin Pipeline DSL."
```

### **Documento de Requisitos del Producto (PRD): Kotlin Pipeline DSL**

#### **1. Introducción**

Este documento define los requisitos para el motor **Kotlin Pipeline DSL**. El sistema **MUST** permitir a los usuarios definir y ejecutar pipelines de CI/CD como código, priorizando la seguridad, la productividad y la extensibilidad.

#### **2. User Personas**

- **Helena, Ingeniera DevOps:** Requiere fiabilidad, facilidad de mantenimiento y un sistema de plugins estable.
- **David, Desarrollador Backend:** Requiere un DSL potente en un lenguaje familiar con excelente soporte de IDE.
- **Silvia, Arquitecta de Seguridad:** Requiere un sandboxing robusto por defecto y control estricto sobre la ejecución de código.

#### **3. Requisitos Funcionales (FR)**

##### **FR-1: DSL Declarativo para Pipelines**

- **FR-1.1:** El script **MUST** tener un bloque raíz `pipeline { ... }`.
- **FR-1.2:** El bloque `pipeline` **MUST** contener un bloque `agent { ... }` que defina el entorno de ejecución principal.
  - **FR-1.2.1:** Para el MVP, el único `agent` soportado **MUST** ser `docker { image = "..." }`.
- **FR-1.3:** El pipeline **MUST** contener un bloque `stages { ... }` que agrupe una o más etapas.
- **FR-1.4:** El bloque `stages` **MUST** contener uno o más bloques `stage("...") { ... }`.
- **FR-1.5:** Cada `stage` **MUST** contener un bloque `steps { ... }` que define las acciones a ejecutar.
- **FR-1.6: Ejemplo de Sintaxis**

<!-- end list -->

```kotlin
// Archivo: mi-app.pipeline.kts
pipeline {
    agent {
        docker { image = "openjdk:21-jdk" }
    }
    stages {
        stage("Build & Test") {
            steps {
                sh("./gradlew build")
            }
        }
    }
}
```

##### **FR-2: Biblioteca de Pasos (Steps) del MVP**

- **FR-2.1:** El sistema **MUST** proveer un paso `sh(command: String)`.
  - **FR-2.1.1:** Este paso **MUST** ejecutar el `command` dentro del `agent` Docker definido.
  - **FR-2.1.2:** El paso **MUST** fallar si el comando devuelve un código de salida distinto de cero.
- **FR-2.2:** El sistema **MUST** proveer un paso `echo(message: String)` que imprima a los logs del build.
- **FR-2.3:** El sistema **MUST** proveer un paso `checkout(scm: Scm)` para clonar el repositorio SCM principal.

##### **FR-3: Motor de Ejecución**

- **FR-3.1:** El motor **MUST** parsear y compilar un fichero `.pipeline.kts`.
- **FR-3.2:** El motor **MUST** ejecutar las `stages` en el orden definido (secuencialmente para el MVP).
- **FR-3.3:** El fallo de cualquier `step` **MUST** causar el fallo de su `stage` y la detención inmediata del pipeline, resultando en un estado de `FAILURE`.

##### **FR-4: Seguridad y Sandboxing**

- **FR-4.1:** **Aislamiento de Agente:** Toda ejecución de `sh` **MUST** ocurrir dentro del contenedor Docker. El acceso al sistema de ficheros del host está prohibido (`MUST NOT`).
- **FR-4.2:** **Sandbox del Script:** La ejecución del propio script `.kts` y de los plugins **SHOULD** realizarse dentro de un GraalVM Isolate con políticas de seguridad restrictivas. Esta es una prioridad alta post-MVP.

##### **FR-5: Arquitectura de Plugins**

- **FR-5.1:** El sistema **MUST** definir una interfaz pública `Step`.
- **FR-5.2:** El sistema **MUST** descubrir implementaciones de `Step` en ficheros JAR utilizando `java.util.ServiceLoader`.
- **FR-5.3:** El sistema **SHOULD** cargar cada plugin en un `ClassLoader` aislado.

##### **FR-6: Hoja de Ruta de Compatibilidad de Pasos con Jenkins (Post-MVP)**

La implementación de estos pasos es el objetivo post-MVP para alcanzar paridad con Jenkins.


| id                         | step (Kotlin)                                                           | Prioridad              |
| :------------------------- | :---------------------------------------------------------------------- | :--------------------- |
| `jenkins.git`              | `git(url: String, ...)`                                                 | Alta (MVP+)            |
| `jenkins.withCredentials`  | `withCredentials(bindings: List<CredentialBinding>, block: () -> Unit)` | Alta (MVP+)            |
| `jenkins.archiveArtifacts` | `archiveArtifacts(artifacts: String, ...)`                              | Alta (MVP+)            |
| `jenkins.junit`            | `junit(testResults: String, ...)`                                       | Alta (MVP+)            |
| `jenkins.timeout`          | `timeout(time: Long, unit: TimeUnit, block: () -> Unit)`                | Alta (MVP+)            |
| `jenkins.script`           | `script(block: ScriptContext.() -> Unit)`                               | Alta (MVP+)            |
| `jenkins.dir`              | `dir(path: String, block: () -> Unit)`                                  | Alta (MVP+)            |
| `jenkins.withEnv`          | `withEnv(vars: List<String>, block: () -> Unit)`                        | Alta (MVP+)            |
| `jenkins.docker.inside`    | `docker.image("...").inside { ... }`                                    | Alta (MVP+)            |
| `jenkins.input`            | `input(message: String, ...)`                                           | Media (Post-MVP)       |
| `jenkins.retry`            | `retry(count: Int, block: () -> Unit)`                                  | Media (Post-MVP)       |
| `jenkins.parallel`         | `parallel(branches: Map<String, () -> Unit>, ...)`                      | Media (Post-MVP)       |
| `jenkins.stash`            | `stash(name: String, ...)` / `unstash(name: String)`                    | Media (Post-MVP)       |
| `jenkins.aws.withAWS`      | `withAWS(credentialsId: String, ...)`                                   | Media (Plugin Externo) |
| `jenkins.jfrog.rtUpload`   | `rtUpload(serverId: String, spec: String)`                              | Media (Plugin Externo) |
| `jenkins.matrix`           | Directiva`matrix { ... }`                                               | Baja (Avanzado)        |

#### **4. Requisitos No Funcionales (NFR)**

- **NFR-1: Rendimiento:** El sistema **SHOULD** cachear los scripts compilados para minimizar la latencia en ejecuciones repetidas.
- **NFR-2: Usabilidad:** Los mensajes de error del DSL **MUST** ser claros, indicando la línea y el contexto del fallo. El DSL **MUST** estar diseñado para un autocompletado efectivo en el IDE.
- **NFR-3: Documentación:** La documentación pública **MUST** incluir una guía de inicio rápido, una guía de migración desde Jenkins y una referencia completa de la API del DSL.

\--- DOCUMENT END: PRD ---
