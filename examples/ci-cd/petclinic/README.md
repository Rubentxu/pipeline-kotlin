# PetClinic CI/CD — a complete pipeline on a real project

El pipeline [`petclinic.pipeline.kts`](petclinic.pipeline.kts) construye un proyecto Maven de
verdad —[spring-petclinic](https://github.com/spring-projects/spring-petclinic) en un commit
fijo—, separa sus tres suites de pruebas, guarda los informes de la corrida, publica lo que
construyó como imagen OCI por su digest y lo levanta en un staging efímero que no deja nada
atrás.

The pipeline builds a real Maven project —[spring-petclinic](https://github.com/spring-projects/spring-petclinic)
at a pinned commit— separates its three test suites, keeps the reports of the run, publishes what
it built as an OCI image named by its digest and raises it in an ephemeral staging that leaves
nothing behind.

> **Una suite que no corrió no es una suite que pasó.** / **A suite that did not run is not a
> suite that passed.**
>
> El proyecto trae una suite que necesita un motor de contenedores. El worker no lo tiene, así que
> esa suite se omite y el build queda verde. El pipeline lo dice (`containers-suite=NOT_RUN` con su
> motivo) y la consola lo deja escrito: una suite omitida nunca sale como una suite que pasó.
>
> The project carries a suite that needs a container engine. The worker has none, so that suite is
> skipped and the build stays green. The pipeline says so (`containers-suite=NOT_RUN` with its
> reason) and the console keeps the sentence: a skipped suite never comes out as a suite that
> passed.

---

## ES

### Qué hace

| Etapa | Qué responde |
|---|---|
| `prepare` | `source-commit=<sha>`: el commit exacto que se construyó |
| `quality` / `unit` / `integration` | `unit-tests=` etc. con fallos, errores y omitidos de cada suite |
| `containers` | `containers-suite=NOT_RUN containers-reason=<motivo>` cuando el worker no tiene motor |
| `package` | `verify` completo: la cobertura que sale es la de los tests que corrieron, y el SBOM CycloneDX |
| `coverage` | `coverage=<porcentaje> threshold=<piso>`; bajo el piso termina la corrida |
| `artifacts` | JaCoCo, SBOM e informes JUnit archivados, cada uno con su digest en la consola |
| `publish` | `published-digest=sha256:...`: la imagen, nombrada por su digest |
| `manifests` / `deliver` | staging efímero por digest, `health` y `vets` responden, y el staging queda vacío |

### Cómo se ejecuta

El pipeline se entrega a un job normal de Jenkins de dos formas, la misma que cualquier
Jenkinsfile:

**Inline** — en la configuración del job (*PipelineK Flow Definition*), elige la fuente
*Inline script* y pega el contenido de [`petclinic.pipeline.kts`](petclinic.pipeline.kts).

**Repository (from SCM)** — elige la fuente *Repository* y rellena:

| Campo | Valor |
|---|---|
| Repository URL | `https://github.com/Rubentxu/pipeline-kotlin.git` |
| Repository ref | `main` |
| Script path | `examples/ci-cd/petclinic/petclinic.pipeline.kts` |
| Repository credential | *(none)* para un repo público; una credencial del store si es privado |

El worker clona el repositorio en el ref dado y lee el guion del path dado. La credencial se
elige del desplegable del formulario, que lista las credenciales que el job puede ver.

El job nace un worker de la plantilla de flota que el operador configura. Los valores que este
pipeline lee del entorno son:

| Variable | Qué dice | Ejemplo |
|---|---|---|
| `LAB_DIR` | Directorio de trabajo del build en el volumen del worker | `/var/lib/pipelinek/spool/lab` |
| `APP_SOURCE_URL` | Repositorio del proyecto a construir | `https://github.com/spring-projects/spring-petclinic.git` |
| `APP_SOURCE_COMMIT` | Commit exacto a construir | `500158f732419217507c7656904b8e6aa1bcc0d6` |
| `COVERAGE_MIN` | Piso de cobertura de líneas | `60` |
| `IMAGE_REGISTRY` | Registry donde se publica, tal como lo ve el worker | `localhost:5000` |
| `IMAGE_PULL_REGISTRY` | El mismo registry, como lo ve el nodo que levanta el staging | `localhost:5000` |
| `IMAGE_REPOSITORY` / `IMAGE_TAG` | Repositorio y etiqueta de la imagen | `lab/petclinic` / `petclinic` |
| `BASE_IMAGE` | Imagen base del `crane append`, por digest | `...@sha256:...` |
| `STAGING_NAMESPACE` | Namespace efímero del staging | `petclinic-staging` |
| `STAGING_SERVICE` / `STAGING_PORT` | Servicio y puerto del humo | `staging` / `8080` |
| `STAGING_KUBECONFIG` | No se define: la trae la credencial `staging-kubeconfig` del job | — |

La credencial `staging-kubeconfig` es una credencial de **fichero** cuyo contenido es un
kubeconfig con permiso sobre el namespace del staging y sobre nada más.

### Cómo se depura un fallo de prueba

1. La consola del build dice la etapa y la suite: `unit-failures=2` manda a los informes de
   `artifacts/surefire-reports/unit/`, que el build archiva y la página del build sirve para
   descargar.
2. El registro de la etapa termina con las últimas 40 líneas del log de Maven: el fallo está ahí,
   con su stack.
3. Si la etapa que falló es `containers` y el motivo es `no-container-engine`, no es un fallo: es
   la suite que el worker no puede correr, y el pipeline la informa como `NOT_RUN`.

### Cómo se repite sin reconstruir la plataforma

El pipeline no construye nada de la plataforma: consume lo que la instalación ya publica. Se
vuelve a correr cambiando sólo los valores de la plantilla del fleet —otro commit, otra etiqueta
de imagen, otro piso de cobertura— sin tocar el plugin, el worker ni el clúster. El staging se
levanta y se retira en cada corrida, así que una repetición no encuentra nada de la anterior.

---

## EN

### What it does

| Stage | What it answers |
|---|---|
| `prepare` | `source-commit=<sha>`: the exact commit that was built |
| `quality` / `unit` / `integration` | `unit-tests=` etc., with each suite's failures, errors and skips |
| `containers` | `containers-suite=NOT_RUN containers-reason=<reason>` when the worker has no engine |
| `package` | the full `verify`: the coverage that comes out is the coverage of the tests that ran, and the CycloneDX SBOM |
| `coverage` | `coverage=<percent> threshold=<floor>`; under the floor the run ends |
| `artifacts` | JaCoCo, SBOM and JUnit reports archived, each one's digest on the console |
| `publish` | `published-digest=sha256:...`: the image, named by its digest |
| `manifests` / `deliver` | an ephemeral staging raised by digest, `health` and `vets` answering, and the staging left empty |

### How to run it

The pipeline reaches a normal Jenkins job in two ways, the same as any Jenkinsfile:

**Inline** — in the job's configuration (*PipelineK Flow Definition*), choose the *Inline script*
source and paste the contents of [`petclinic.pipeline.kts`](petclinic.pipeline.kts).

**Repository (from SCM)** — choose the *Repository* source and fill in:

| Field | Value |
|---|---|
| Repository URL | `https://github.com/Rubentxu/pipeline-kotlin.git` |
| Repository ref | `main` |
| Script path | `examples/ci-cd/petclinic/petclinic.pipeline.kts` |
| Repository credential | *(none)* for a public repository; a store credential for a private one |

The worker clones the repository at the given ref and reads the script at the given path. The
credential is chosen from the form's dropdown, which lists the credentials the job can see.

The job's worker is born from the fleet template the operator configures. The values this
pipeline reads from the environment are:

| Variable | What it says | Example |
|---|---|---|
| `LAB_DIR` | The build's working directory on the worker's volume | `/var/lib/pipelinek/spool/lab` |
| `APP_SOURCE_URL` | The repository of the project to build | `https://github.com/spring-projects/spring-petclinic.git` |
| `APP_SOURCE_COMMIT` | The exact commit to build | `500158f732419217507c7656904b8e6aa1bcc0d6` |
| `COVERAGE_MIN` | The floor of line coverage | `60` |
| `IMAGE_REGISTRY` | The registry to publish to, as the worker sees it | `localhost:5000` |
| `IMAGE_PULL_REGISTRY` | The same registry, as the node raising the staging sees it | `localhost:5000` |
| `IMAGE_REPOSITORY` / `IMAGE_TAG` | The image's repository and tag | `lab/petclinic` / `petclinic` |
| `BASE_IMAGE` | The base image of the `crane append`, by digest | `...@sha256:...` |
| `STAGING_NAMESPACE` | The staging's ephemeral namespace | `petclinic-staging` |
| `STAGING_SERVICE` / `STAGING_PORT` | The smoke's service and port | `staging` / `8080` |
| `STAGING_KUBECONFIG` | Not defined: it comes with the job's `staging-kubeconfig` credential | — |

The `staging-kubeconfig` credential is a **file** credential whose contents are a kubeconfig with
power over the staging's namespace and nothing else.

### How to debug a failing test

1. The build's console names the stage and the suite: `unit-failures=2` points at the reports of
   `artifacts/surefire-reports/unit/`, which the build archives and the build page serves for
   download.
2. The failing stage's log ends with the last 40 lines of the Maven log: the failure is there,
   with its stack.
3. If the stage that failed is `containers` and the reason is `no-container-engine`, that is not a
   failure: it is the suite the worker cannot run, and the pipeline reports it as `NOT_RUN`.

### How to repeat it without rebuilding the platform

The pipeline builds nothing of the platform: it consumes what the installation already publishes.
It runs again by changing only the fleet template's values — another commit, another image tag,
another coverage floor — without touching the plugin, the worker or the cluster. The staging is
raised and taken away in every run, so a repeat finds nothing of the one before.
