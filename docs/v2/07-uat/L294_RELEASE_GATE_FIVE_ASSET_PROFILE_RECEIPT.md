# L294 — releaseGate: perfil de cinco assets, y los mismos bytes probados como consumidor

**Caja:** `docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md:294`
**Cerrada sobre:** `main@2466b308`
**Naturaleza:** esta caja **no preguntó por un cambio de código este ciclo**. Sus dos mitades ya
estaban implementadas; lo que faltaba era una medición, y esa medición se ha ejecutado.

---

## 1. Lo que la caja pedía, descompuesto en lo que se puede observar

> En el `releaseGate`, exigir exactamente el perfil vigente de cinco assets: runtime ZIP, SBOM,
> SDK ZIP, BOM JSON y SHA256SUMS; comprobar **completitud e integridad**, sin extras, y probar
> los mismos bytes como consumidor. Mantener ADR-PK-DF-001 Opción A mientras no se autorice
> expresamente la B de PR #142. Publicación Maven remota = `BLOCKED_EXTERNAL` si faltan
> destino/credenciales.

| Requisito | Dónde vive la autoridad | Estado medido |
| --- | --- | --- |
| perfil exacto de cinco, en el productor | `releaseGate` / `candidateAdmission` | **APLICADA** desde `13590e76` + `b078bb63` |
| perfil exacto de cinco, en el publicador | `scripts/release-pipelinek.sh` | **APLICADA** desde `1f8e2400` |
| completitud e integridad (local) | el propio `SHA256SUMS` de la candidata | **MEDIDA** en §2 |
| "sin extras" sobre el conjunto **remoto** | el certifier, gate `assets.distribution-allowlist` | `BLOCKED_EXTERNAL` **por diseño** |
| probar los mismos bytes como consumidor | un consumidor limpio | **MEDIDA** en §3 |
| mantener ADR-PK-DF-001 Opción A | la propia ADR | **HELD** (§4) |
| publicación Maven remota | destino/credenciales externos | `BLOCKED_EXTERNAL` (la ADR ya lo declara así) |

## 2. Integridad y completitud, sobre los bytes de la candidata

La candidata que el propio repositorio produjo es `sha256:0c2f6884…`, `source_commit`
`b078bb63`. Sus bytes siguen en disco, así que la medición es sobre el artefacto real y no sobre
una reconstrucción:

```text
$ sha256sum -c SHA256SUMS
pipelinek-0.62.0.zip: La suma coincide
pipelinek-0.62.0.sbom.json: La suma coincide
pipelinek-0.62.0.sdk.zip: La suma coincide
pipelinek-bom-0.62.0.json: La suma coincide
```

Dos propiedades que la tabla de digests por sí sola no prueba, y que sí se comprobaron:

- **El manifiesto nombra los cinco, no cuatro.** `distribution-manifest.json` declara `asset`,
  `sbom`, `sdk`, `bom` y `sha256sums`, cada uno con su digest. El asset que originó todo el
  trabajo de esta casilla —el encogimiento silencioso de `covered` cuando faltaba el SBOM— ya
  es inalcanzable porque el productor falla cerrado.
- **El conjunto está cerrado sobre sí mismo.** El manifiesto registra el digest del propio
  `SHA256SUMS` (`1a4d514a…`) y ese es el digest del fichero que está en disco. Un manifiesto que
  no declarara su propio `SHA256SUMS` no podría ser completo por construcción; aquí lo declara y
  concuerda.

## 3. Los mismos bytes, como consumidor

Esta es la parte que la casilla nombraba como única restante, y la que la casilla siguiente
(L378) había ejecutado sobre un ZIP reconstruido. Se repitió aquí **sobre los bytes que la
candidata nombra**, que no es lo mismo: son los que `candidateAdmission` hashtó y los que su
`SHA256SUMS` firma.

Montaje: espejo loopback con los cuatro assets de `build/distributions/` más el
`SHA256SUMS` de `build/candidate/`; `PIPELINEK_HOME` nuevo; instalador real
(`scripts/install-pipelinek.sh`), el mismo que usa el consumidor.

```text
[INFO]  Digest verified: 0c2f6884f3740893c9bfa349841f9615094dd00a28161367e71d5c750d80e50d
[INFO]  Installed binary reports: pipeline 0.62.0
[INFO]  Version 0.62.0 installed at <HOME>/versions/0.62.0

$ pipelinek version
pipeline 0.62.0

$ pipelinek validate pipeline.pipeline.kts
[PipelineK] Compiling pipeline
VALIDATION SUCCESSFUL

$ pipelinek run pipeline.pipeline.kts
Discovered external Step plugins: example.uppercase, example.repeat, scm-git, http, junit, utilities
Discovered external directive plugins: example.uppercase.UppercaseDirectiveContributor, example.lock.LockContributor
Discovered external event definitions: example.uppercase.applied
[PipelineK] [step: consumer] consumer/echo-0
hola consumidor
[PipelineK] Finished: success
Pipeline finished with SUCCESS
```

Dos cosas que esto prueba y que una inspección del repositorio no puede:

1. **La identidad sobrevive al viaje.** El instalador exige identidad EXACTA en runtime, y las
   cuatro caras de esa identidad —`product_version`, `implementation_version`, `archive_root` y
   `candidate_id`— coinciden en `0.62.0` / `0c2f6884…`. Un candidato cuya identidad fuera función del historial
   de builds fallaría aquí, y es exactamente el defecto que L378 encontró en dos de los tres
   plugins de ejemplo — corregido, pero que demuestra que la clase de defecto es real y que esta
   puerta la detecta.
2. **El asset ZIP es ejecutable de verdad.** No "contiene lo que debe": instala, valida y corre
   un pipeline que descubre seis plugins de Step externos y dos colaboradores de directiva.

## 4. Lo que NO se hizo aquí, y por qué es correcto no hacerlo

- **La igualdad exacta del conjunto remoto.** La caja misma lo declara fuera de su alcance: es la
  autoridad del certifier (`assets.distribution-allowlist`). `ADR-PK-DF-002` decisión 2b prohíbe
  crear aquí un segundo sistema de gating que sustituya al certifier, así que queda
  `BLOCKED_EXTERNAL` **por diseño y no por descuido**.
- **ADR-PK-DF-001 Opción B (PR #142).** No se ha implementado nada de ella. La ADR sigue
  `accepted` con Opción A como ley ejecutable, tal y como la caja lo pedía. La única lectura de
  contrato que quedaba tolerante (`sdk`/`bom` opcionales en el esquema publicado) no se tocó
  porque cambiarla es cambio de contrato —ADR— y no corrección de bug; lo que se hizo fue quitar
  el modo en que esa opcionalidad se cumplía en silencio.
- **Un `publish` real contra GitHub.** Sigue `NOT_RUN`: es autorización externa, no un cambio de
  código, y el propio producto del publicador ya no tiene Tolerance que ejercitar.

## 5. Informe de verificación

```text
Changed:
- (ningún cambio de producción; sólo documentación)
- docs/v2/07-uat/L294_RELEASE_GATE_FIVE_ASSET_PROFILE_RECEIPT.md   — este recibo
- docs/v2/05-roadmap/QUALITY_VALUE_ADDENDUM.md:294                  — cerrada

Affected SUT:
- la identidad de la candidata y la frontera de autoridad del conjunto de assets

Verification executed:
- sha256sum -c        sobre el SHA256SUMS de la candidata -> 4/4 coinciden
- cierre del conjunto el digest que el manifiesto declara para SHA256SUMS coincide con el fichero
- install             el instalador real consumió 0c2f6884… y verificó el digest
- version/validate/run  pipeline 0.62.0, VALIDATION SUCCESSFUL, Pipeline finished with SUCCESS

Evidence reused:
- la producción estricta (13590e76, b078bb63, 1f8e2400), medida en el ciclo anterior y no
  re-ejecutada aquí: la caja no la pedía, pedía la medición del artefacto.

Verification deliberately not executed:
- publish real contra GitHub (autorización externa)
- suite `check` — no cambió una línea de código de producción; la escalera dice docs-only:
  validación de enlaces, sin Gradle.

Unknown impact:
- si el certifier aceptaría hoy este conjunto de cinco (gate remoto; BLOCKED_EXTERNAL).

Result: PASS
Full verification required now: NO
```

## 6. Una observación que no se corrigió, y por qué no se corrige aquí

`candidate-handoff.json` declara `candidate_sequence: 1` y `distribution_manifest.built_at` /
`sbom.built_at` / `sdk.built_at` / `bom.built_at` como el **commit**, no como un instante. Eso
significa que esos cuatro campos son funciones del commit y no del reloj — lo correcto para
reproducibilidad, y la razón de que `candidate_id` sea un digest y no una marca temporal. Se deja
constancia porque el nombre del campo (`built_at`) sugiere lo contrario, y un lector futuro que
lo tome por un timestamp intentará hacer reproducible algo que ya lo es por otra razón. Renombrar
un campo de un esquema publicado es cambio de contrato; anotarlo aquí es lo que corresponde.