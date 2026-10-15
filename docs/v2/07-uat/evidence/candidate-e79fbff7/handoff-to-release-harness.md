# Handoff a `pipelinek-release-harness` — candidato local (train 0.61.0, sequence 1)

- **Fecha:** 2026-10-15
- **Estado de este documento:** preparado, **no ejecutado**. El ZIP vive en el árbol de build
  de este worktree y no está publicado: el handoff real requiere publicar el Release (acción
  del operador con credenciales, fuera del alcance de esta sesión).
- **Candidato:** `sha256:1b1a2c01715c92c5125e727f311c63b77f2fd6d29c05ecef99178660766860b7`
- **SHA de origen:** `e79fbff772561561520f5a866a2e3c7d258ca78a` (worktree
  `worktree/test-harness-brave-harbor-6920`, árbol limpio en el momento de construir)
- **Evidencia local:** este directorio (`candidate-receipt.json`, `candidate-handoff.json`,
  `distribution-manifest.json`, `pipelinek-0.61.0.sbom.json`, `zip.sha256`, `README.md`).

---

## 1. Identidad medida (no reconstruida)

```text
asset               pipelinek-0.61.0.zip
bytes               92 985 677
sha256              1b1a2c01715c92c5125e727f311c63b77f2fd6d29c05ecef99178660766860b7
archive root        pipelinek-0.61.0
Implementation-Version (JAR) 0.61.0
release train       0.61.0       candidate sequence 1
product identity    AssetVersion == ArchiveRootVersion == EmbeddedVersion == RuntimeVersion
                    (comparación de token EXACTO, verificada por emit-candidate-receipt.py)
candidate_id        sha256:<zip>            source_commit e79fbff7…
sbom sha256         50d02584603a1c6b482f468976a21d0d8334c47e858fcdcdee6ac92c517af707
manifest sha256     cbd3090c1e8e432fe437c86bee5430d35a1b2b9815e758bcffc01031f4801b42
```

Verificado en local, con las herramientas del repositorio:

```text
cd v2 && ./gradlew :pipeline-release:candidateAdmission -Pcandidate.tag=v0.61.0-rc-local   -> exit 0
python3 scripts/emit-candidate-receipt.py --zip … --manifest … --handoff … --sbom … \
        --expect-sha e79fbff7…           -> verdict PASS, 16/16 checks
```

## 2. Qué falta para el handoff real (acción del operador)

```text
1. Tag anotado sobre el SHA de build:  v0.61.0-rc1 -> e79fbff7…   (o un SHA posterior si se
   decide reconstruir: AGENTS.md §"Release candidates" prohíbe re-taggear una candidata ya
   enviada, no impide construir otra nueva)
2. GitHub Release (prerelease) en Rubentxu/pipeline-kotlin con el asset
   pipelinek-0.61.0.zip y el sidecar .sha256 subidos SIN recomprimir.
3. Verificación del asset re-descargado: sha256 == el de §1. Si difiere, la candidata no se
   envía: es un defecto de build, no un desajuste tolerable.
4. Aviso al harness (fuera de este repositorio): el harness consume
   `evidence/<candidate>/verdict.json` y publica su veredicto; este repo no abre issues ni
   empuja veredictos contra el harness (AGENTS.md §"Frontera de responsabilidad").
5. A partir de ahí, el consumidor mínimo de este repo puede leerlo:
   `python3 scripts/consult-harness-verdict.py --candidate v0.61.0-rc1`
   (requiere `docs/v2/07-uat/HARNESS_PUBLISHER_ALLOWLIST.txt`, que ya es la autoridad de
   quién puede publicar un veredicto).
```

## 3. Qué debe verificar el harness (y qué no puede verificar este repositorio)

Esta candidata se construyó **antes** de conocerse la roje pre-existente del gate local
(5 filas de pines de certificación en `:pipeline-application:test`, ver
`docs/v2/07-uat/B1_HARNESS_ROUND_GATE_STATE_RECEIPT.md` §2). Consecuencias honestas:

- el ZIP es materialmente válido y su admisión pasó (`candidateAdmission` exit 0), pero
  **no** se ha ejecutado un L-ROUND verde sobre su SHA: la roje es de pines, no del
  empaquetado;
- por tanto este handoff NO afirma `PRODUCT_GATE` verde. Si el harness exige un L-ROUND verde
  del SHA, esa condición se cumple cuando el drift de pines se resuelva (decisión de
  version-bump/owner) y se reconstruya la candidata sobre un SHA verde;
- lo que el harness debe poder reproducir sin ayuda: bytes de §1, y su propio corpus externo
  (PetClinic y resto de la matriz). Nada de eso se ejecuta en este repositorio.

## 4. Qué NO contiene esta candidata

```text
- no hay Release, tag ni asset publicado por este trabajo;
- no hay veredicto del harness: cualquier afirmación de certificación externa sería inventada;
- no se ha tocado la batería UAT local (sigue fuera de los gates locales por diseño, §5b0d08a2);
- no se ha reescrito ni recomprimido el ZIP después de medirlo.
```
