# DSL `pipeline.kts` — evolución compatible y agent-first

## 1. Regla de compatibilidad

La forma existente:

```kotlin
withCredentials(
    usernamePassword(
        credentialsId = "nexus",
        usernameVariable = "NEXUS_USER",
        passwordVariable = "NEXUS_PASS",
    )
) {
    sh("./gradlew publish")
}
```

mantiene su semántica: binding explícito a variables/forma Jenkins-compatible.

No se reinterpreta mediante autodetección.

## 2. Nuevo scope por profile

Forma compacta preferida:

```kotlin
withCredentials(profile("nexus-release")) {
    sh("./gradlew publish")
}
```

Múltiples identidades:

```kotlin
withCredentials(
    profile("nexus-release"),
    profile("github-release"),
) {
    sh("./gradlew publish")
    step("github.release.create", githubReleaseInput(...))
}
```

## 3. IR separado

Aunque comparta el nombre `withCredentials`, el overload de `CredentialProfileRef` no debe bajar al payload legacy.

Recomendación:

```text
withCredentials(CredentialsBinding...)
    → StepSpec.WithCredentialsBlock              // congelado

withCredentials(CredentialProfileRef...)
    → RegistryBlockSpec("core.credentials.scope") // nuevo v1
```

Esto permite evolucionar el nuevo modelo sin alterar fingerprints antiguos.

## 4. DSL explícito avanzado

Para casos que no merecen un profile persistente:

```kotlin
withCredentials(
    credential("nexus") {
        ref("nexus/releases")
        audience("nexus.internal")
        minimumPosture(ISOLATED_PROCESS_EXPOSURE)
        projection {
            gradleProperties()
        }
    }
) {
    sh("./gradlew publish")
}
```

La forma avanzada debe compilar a un ADT tipado, no a un mapa libre.

## 5. `withProfile`

Credentials son sólo una parte del execution policy. Se propone una scope más general:

```kotlin
withProfile("release") {
    withCredentials(profile("nexus-release")) {
        sh("./gradlew publish")
    }
}
```

Un `ExecutionProfile` puede incluir:

- credential profiles;
- sandbox/trust profile;
- network/egress policy;
- timeout;
- retry policy si ya tiene semántica canónica;
- output projection;
- workspace rules;
- approval/policy metadata futura.

## 6. Profiles no son estado mutable de pipeline

El script sólo contiene refs:

```kotlin
profile("github-release")
```

La resolución ocurre en runtime contra configuración operativa.

Por defecto los profiles viven fuera del repo:

```text
${XDG_CONFIG_HOME:-~/.config}/pipelinek/profiles.toml
${XDG_CONFIG_HOME:-~/.config}/pipelinek/projects/<project-id>/profiles.toml
```

Opcionalmente se puede pasar un fichero explícito con CLI para entornos reproducibles, pero PipelineK no crea `.pipelinek/` automáticamente.

## 7. Steps y credential requirements

El scope no sabe qué Step hay dentro. Cada Step resuelve su requirement al ejecutarse.

```kotlin
withCredentials(profile("github")) {
    checkout(scmGit("git@github.com:org/repo.git"))
    step("github.release.create", ...)
}
```

puede producir dos estrategias distintas:

```text
checkout → SSH signer
release  → HTTP broker/proxy
```

## 8. Command facade en DSL

No se añade un executor nuevo. Si se ofrece azúcar argv-safe:

```kotlin
command("gh", "release", "create", version)
```

su primera implementación debe bajar a `core.sh` mediante encoder canónico.

Alternativa más conservadora para el primer slice: no exponer aún `command()` y usar `sh()` hasta que la CLI inline esté caracterizada.

## 9. Generic `step`

Primitive abierta:

```kotlin
step(
    "vendor.operation",
    encodedInput,
)
```

Las extensiones reales deben aportar DSL tipado que baje a `registryStep`, evitando strings en uso normal:

```kotlin
githubRelease { ... }
markdownRender { ... }
terraformPlan { ... }
```

## 10. Anti-patterns

No introducir:

```kotlin
secretlessSh(...)
secretlessExec(...)
secretlessGit(...)
```

Secretless es una capacidad/policy ortogonal, no una segunda familia de Steps.

