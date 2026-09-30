# pipeline-kotlin — Responsabilidades y especificación

## 1. Misión del repositorio

`pipeline-kotlin` es el **fast lane**.

Su objetivo es maximizar:

- entrega de valor;
- calidad local suficiente para no enviar basura al harness;
- velocidad de feedback;
- candidatas frecuentes e inmutables.

No debe absorber las baterías pesadas del release harness.

## 2. Responsabilidades exclusivas

- fuente del producto;
- `ProductVersion`;
- SemVer/Conventional Commits;
- build y reproducibilidad local;
- ZIP canónico;
- archive root;
- JAR manifests;
- SBOM;
- `distribution-manifest.json`;
- checksum material;
- candidate handoff;
- documentación del producto;
- instalador manual oficial;
- local cheap identity admission.

## 3. Candidate admission local

Debe ser deliberadamente barato en comparación con el harness.

### Development loop

- tests afectados;
- detekt afectado;
- architecture fitness afectado;
- no full suite por cada commit.

### Candidate materialization

Antes de handoff:

1. full suite propia requerida por política de release;
2. `distZip` reproducible;
3. SBOM;
4. checksums;
5. `distribution-manifest.json`;
6. exact identity sanity;
7. installed binary smoke mínimo;
8. candidate receipt.

## 4. Cheap identity gate

El productor debe poder demostrar a bajo coste:

```text
expected ProductVersion
== asset version
== archive root version
== primary application JAR Implementation-Version
== `pipelinek version`
== distribution-manifest.version
```

Esto no sustituye la verificación independiente del harness.

## 5. Distribution manifest

Debe generarse desde el build y describir los bytes reales.

Prohibido:

- modificarlo durante promoción;
- declarar una versión distinta del contenido;
- reescribirlo downstream.

## 6. Candidate material

La candidata se construye con **target final ProductVersion**.

Ejemplo 0.44:

```text
rootProject.version = 0.44.0
pipelinek-0.44.0.zip
```

La condición Candidate vive fuera de `ProductVersion`.

## 7. Continuidad de main

Tras publicar una candidata:

```text
DO NOT WAIT FOR HARNESS
```

El agente debe:

1. registrar candidate handoff;
2. continuar siguiente WorkItem independiente;
3. integrar findings del harness cuando lleguen;
4. producir nueva candidata cuando tenga sentido.

## 8. Harness failures

Un FAIL externo:

- no rebobina main;
- abre/actualiza finding por fingerprint;
- entra en priorización normal según severidad;
- cuando se corrige, la siguiente candidata hereda el fix.

## 9. Qué NO pertenece aquí

No traer al upstream:

- mise clean-room matrix;
- asdf clean-room matrix;
- 5 toolchains externas;
- kill/restart extensa;
- concurrency stress larga;
- container parity;
- benchmarks largos;
- PATH poisoning suite;
- install manager coexistence suite;
- cross-project corpus completo.

## 10. Instalador manual

Se mantiene como stable-only salvo decisión posterior.

Debe:

- consumir `SHA256SUMS`;
- instalar en staging temporal;
- validar archive layout;
- validar runtime version exacta;
- mover atómicamente a `versions/V` sólo tras PASS;
- fallar y limpiar ante cualquier mismatch.

## 11. Documentación

La docs pública debe indicar claramente:

1. mise como canal recomendado mientras proceda;
2. asdf como alternativa;
3. ZIP/manual como fallback;
4. un único resolver PipelineK activo por shell/proyecto;
5. cómo diagnosticar `command -v`, `mise which`, `asdf which`.
