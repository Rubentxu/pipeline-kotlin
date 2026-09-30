# Release Model v2 — Target-Versioned Candidates

## 1. Problema que elimina

El modelo anterior intentaba cumplir simultáneamente:

```text
RC bytes == GA bytes
```

y:

```text
RC runtime version = X.Y.Z-rcN
GA runtime version = X.Y.Z
```

Estas dos condiciones son incompatibles si la versión está embebida en JAR/ZIP.

## 2. Nuevo modelo

Separar:

```text
ProductVersion  != CandidateIdentity
```

Ejemplo:

```text
ProductVersion = 0.44.0
CandidateId    = sha256:18ca1ab5...
CandidateSeq   = 3
ReleaseTrain   = 0.44.0
```

Los bytes ya contienen la versión final objetivo:

```text
pipelinek-0.44.0.zip
pipelinek-0.44.0/
pipeline-application-0.44.0.jar
pipelinek version -> 0.44.0
```

Todavía no son GA: son **candidate material**.

## 3. Flujo

```text
main evolves
  |
freeze candidate point
  |
build target-versioned artifact V
  |
cheap upstream admission
  |
publish/register CandidateId(SHA)
  |
main continues
  |
harness coalesces queue
  |
heavy certification
  |
CERTIFIED exact SHA
  |
promote exact SHA to stable vV
```

## 4. Representación pública de candidatas

Preferencias, en orden:

### A. Candidate registry / GitHub artifact

La candidata no necesita tag SemVer público. Se publica mediante un mecanismo de handoff por SHA.

### B. GitHub prerelease con nombre no-SemVer

Ejemplo:

```text
candidate-0.44.0-3
```

El asset sigue siendo:

```text
pipelinek-0.44.0.zip
```

### C. RC SemVer histórica

Sólo como compatibilidad transitoria. No usar para el modelo definitivo porque vuelve a acoplar estado de certificación con identidad del binario.

## 5. Promotion

La promoción sólo puede hacer:

```text
certified candidate asset
  -> verify SHA
  -> create stable tag/release
  -> upload/copy exact asset bytes
  -> verify remote SHA
```

No hay `sed`, rewrite, rebuild ni rename semántico RC→GA.

## 6. Stable publication

Una vez publicado:

```text
stable release artifact SHA == certified candidate SHA
```

Esto restablece correctamente:

```text
BUILD ONCE
CERTIFY EXACT BYTES
PUBLISH SAME BYTES
```

## 7. Candidate supersession

Una candidata más reciente del mismo train supersede a anteriores no certificadas:

```text
candidate 1 -> SUPERSEDED
candidate 2 -> SUPERSEDED
candidate 3 -> ACTIVE
```

Una candidata ya `CERTIFIED` pero aún no promovida no debe ser superseded automáticamente sin política explícita. Recomendación: la certificación cierra el train para promoción o exige una nueva decisión de operador.

## 8. SemVer

El target `ProductVersion` sigue derivándose en `pipeline-kotlin` con SemVer/Conventional Commits.

El `CandidateSeq` no forma parte de SemVer del producto.
