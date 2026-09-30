# Guía de adopción

## 1. Usuario existente

No necesita cambiar nada.

```kotlin
withCredentials(string("token", "TOKEN")) {
    sh("tool")
}
```

continúa igual.

## 2. Migración voluntaria a profile

Antes:

```kotlin
withCredentials(
    usernamePassword("nexus", "USER", "PASS")
) {
    sh("./gradlew publish")
}
```

Después:

```kotlin
withCredentials(profile("nexus-publish")) {
    sh("./gradlew publish")
}
```

El cambio es explícito y puede cambiar la projection/posture; por eso nunca se realiza automáticamente.

## 3. Agente

Antes:

```bash
TOKEN=... gh release create ...
```

Objetivo:

```bash
pipelinek command --profile github-release -- gh release create ...
```

## 4. Plugin

Antes: plugin inventa su propia lectura de env/token.

Después: declara `CredentialRequirement` y consume una capability preparada por runtime.

## 5. Operador

Mueve configuración de identidad a profiles/provider config bajo XDG y deja el repo libre de secretos/estado operativo.

