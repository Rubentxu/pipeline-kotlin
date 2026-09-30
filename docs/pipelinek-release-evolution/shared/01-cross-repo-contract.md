# Contrato Cross-Repo v2

## 1. Propósito

Definir una frontera estable entre:

- `Rubentxu/pipeline-kotlin`: productor rápido del artefacto.
- `Rubentxu/pipelinek-release-harness`: certifier independiente y pesado.

La separación existe para desacoplar **velocidad de desarrollo** de **profundidad de certificación**.

## 2. Regla de autoridad

```text
pipeline-kotlin
  owns: source, ProductVersion, artifact construction, candidate publication

pipelinek-release-harness
  owns: heavy external certification, queue policy, verdict, stable eligibility
```

El harness puede disparar la publicación estable si se desea operacionalmente, pero no puede transformar la identidad de producto contenida en los bytes.

## 3. Fast lane / hard lane

### Fast lane — upstream

Durante desarrollo:

- tests afectados;
- lint/detekt afectados;
- fitness/arquitectura afectada;
- full suite sólo al materializar candidata o frontera equivalente;
- empaquetado reproducible;
- cheap identity gate;
- publicación de candidata;
- continuar inmediatamente con el siguiente WorkItem.

### Hard lane — harness

- real projects;
- clean install;
- toolchains reales;
- restart/resume;
- concurrency;
- mise/asdf/manual install;
- upgrade/rollback/uninstall;
- PATH/shim poisoning;
- sandbox/security;
- credentials/redaction;
- supply-chain;
- performance;
- engine parity;
- external plugins;
- certificación instalada desde los bytes exactos.

## 4. Candidate identity

La identidad canónica de una candidata es:

```text
CandidateId = SHA256(distribution ZIP)
```

No se usa como identidad material:

- branch;
- Git SHA aislado;
- tag mutable;
- `latest`;
- nombre humano de RC.

Git SHA y target version siguen siendo metadata obligatoria, pero no sustituyen al digest.

## 5. ReleaseTrain

Cada candidata pertenece exactamente a un `ReleaseTrain`:

```text
ReleaseTrainId = target ProductVersion, por ejemplo 0.44.0
```

El harness mantiene como candidata elegible la más reciente del train que no esté superseded.

## 6. Coalescing / latest-wins

Dentro del mismo train:

```text
C1 RECEIVED
C2 RECEIVED -> C1 SUPERSEDED
C3 RECEIVED -> C2 SUPERSEDED
```

El scheduler certifica `latestEligible(train)`.

Una candidata `SUPERSEDED`:

- no es FAIL;
- conserva evidencia histórica;
- nunca puede promocionarse;
- puede terminar el checkpoint seguro ya iniciado, pero no abrir nuevas fases pesadas.

## 7. No starvation entre trains

Política propuesta:

```text
active train: termina latest(train)
next train: coalesce internamente mientras espera
```

Ejemplo:

```text
0.44 C3 ACTIVE
0.45 C1 SUPERSEDED
0.45 C2 QUEUED
```

Cuando 0.44 finaliza, se certifica directamente 0.45 C2.

## 8. Resultado del harness

El harness emite uno de:

```text
CERTIFIED
CERTIFICATION_FAILED
BLOCKED_ENV
SUPERSEDED
NOT_RUN
```

Sólo `CERTIFIED` sobre la candidata activa y no superseded habilita promoción estable.

## 9. Defectos y continuidad upstream

Un `CERTIFICATION_FAILED`:

1. genera/actualiza issue por huella estable;
2. no revierte `main`;
3. no bloquea trabajo independiente;
4. el fix entra en una candidata posterior;
5. el harness re-verifica primero el escenario afectado y luego la batería requerida.

## 10. Ley de no transformación

El harness puede:

- descargar;
- copiar byte a byte;
- instalar;
- ejecutar;
- publicar exactamente los bytes certificados;
- producir evidence/verdict/receipts.

El harness NO puede:

- reconstruir PipelineK;
- modificar ZIP/JAR;
- renombrar una identidad interna RC a GA;
- reescribir `manifest.json` para declarar otra versión;
- reescribir checksums para simular otra identidad;
- alterar el contenido certificado antes de promoción.

## 11. Identidad de producto

Para una candidata de target version `V`:

```text
ProductVersion            = V
asset                     = pipelinek-V.zip
archive root              = pipelinek-V/
Implementation-Version    = V
pipelinek version         = V
manifest.version          = V
```

El estado de candidata se representa fuera del producto mediante `CandidateId`, `ReleaseTrainId` y `candidate-sequence`.

## 12. Seguridad de la frontera

Preferencia:

- upstream puede publicar candidatas;
- harness necesita read sobre candidatas y write sólo si se mantiene promoter automatizado;
- cualquier write upstream del harness queda limitado a stable promotion;
- ningún token del harness debe permitir force-push o sustitución silenciosa de assets certificados.
