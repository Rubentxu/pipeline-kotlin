# Handoff a `pipelinek-release-harness` — v0.56.0-rc3

**Fecha:** 2026-10-12
**Candidata:** v0.56.0-rc3 (train `0.56.0`, sequence 3) — **release correctiva final** sobre
`v0.56.0-rc2` que cierra la deuda técnica de governance.
**Tag:** `v0.56.0-rc3` (anotado, peel = `4d5e8e95` sobre `release/pk1-contract-v0.56.0-rc1`).
**Estado 1.D:** `CANDIDATE_PUBLISHED`.

## Por qué `v0.56.0-rc3` y no solo `v0.56.0-rc2`

`v0.56.0-rc2` cubre el código PK-1 completo (T1-T4, T7-T8, T9-T11, T12-T13) pero deja un
trabajo de governance sin cerrar: el framework `docs/pipelinek-coordinated-evolution/`
vivía untracked en el worktree principal y no formaba parte de la historia. `v0.56.0-rc3`
añade un solo commit governance (docs-only) sobre la misma release branch, cerrando
esa deuda. **Sin reescribir las tags `v0.56.0-rc1` ni `v0.56.0-rc2`**; la candidata
canónica final para `CERTIFIED` es **`v0.56.0-rc3`**.

## Cambios sobre `v0.56.0-rc2`

1. **Governance framework comprometido (commit `4d5e8e95`).** 36 archivos / 2120
   inserciones en `docs/pipelinek-coordinated-evolution/{coordination,acceptance,adrs,agent,reference,roadmap,specifications}/` + `PACKAGE_MANIFEST.json`. Detalle completo en
   `release-receipt.md`.
2. **Sin cambios funcionales.** El binario es idéntico al de `v0.56.0-rc2` salvo timestamps
   de zip; el commit governance es docs-only y no recompila nada.

## Materiales publicados (inmutables)

- **ZIP candidato:** `pipelinek-0.56.0.zip` (88.3 MB).
- **SHA-256 ZIP:** `78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.
- **5 SDK coordinates** a `0.56.0` (idénticas a `v0.56.0-rc1` y `v0.56.0-rc2`).
- **`candidate_id`:** `sha256:78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.

## Batería de certificación (PK-1 final, sin cambios sobre rc2)

- `Pk1ContractTypesTest` (8 casos in-monorepo) — verde.
- `Pk1ContractSurfaceTest` (5 casos, consumer extension) — **5/5 verde** contra
  `-PsdkVersion=0.56.0` con `PIPELINEK_BIN`.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 145+ casos.

## Pasos del lado del harness (esperados)

1. **Re-construir la imagen desde el ZIP publicado.** El `candidate_id`
   (sha256 del ZIP) es la identidad inmutable.
2. **Correr la batería completa** sobre la imagen reconstruida
   (los 145+ casos arriba).
3. **Verificar end-to-end el `--correlation-id`** ejecutando
   `pipeline run --correlation-id <uuid> --db <path> <script>` y
   parseando la línea `pipelinek.launch/1` de stdout.
4. **Verificar el `CONTRACT_SHA256.txt`** (sin cambios desde `v0.56.0-rc1`).
5. **Verificar que el governance framework está versionado**: la candidata debe
   llevar `docs/pipelinek-coordinated-evolution/coordination/MILESTONE_MATRIX.md`
   y `verify_pair_gate.py` (entre otros). Esto distingue rc3 de rc1/rc2.
6. **Emitir el veredicto** (CERTIFIED / BLOCKED). Si es CERTIFIED, las
   9 capabilities EXPERIMENTAL pueden promocionarse a PUBLICADA en un release posterior.

## Estado del bloque PK-1 (final)

```
T1-T18: 18/18 tareas completadas (T5/T6/T10/T13 con implementación inline equivalente)
T19 (rc3): governance framework committed
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces
```

**PK-1 fully complete; ready for `CERTIFIED` + `PAIR_CERTIFIED` with Fabric.**

## Pendiente

- ✅ **Tag + Prerelease en GitHub:** `v0.56.0-rc3` publicada en
  https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc3.
  ZIP `pipelinek-0.56.0.zip` (88.3 MB) subido como asset con
  SHA-256 `78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.
- **Veredicto del harness:** pendiente. Se añadirá al `release-receipt.md` cuando llegue.
- **`v0.56.0-rc1` y `v0.56.0-rc2`** quedan en GitHub como Prereleases históricas
  (no reescritas); la candidata canónica para `PAIR_CERTIFIED` es **`v0.56.0-rc3`**.

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-1 final rc3).
