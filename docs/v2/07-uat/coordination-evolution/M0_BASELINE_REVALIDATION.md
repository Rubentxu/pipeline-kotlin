# WIP-0 — Revalidación de baseline PK para M0 (evolución coordinada)

**Fecha:** 2026-10-10
**Hito:** M0 (bootstrap de la iniciativa PK × Fabric, sin código modificado)
**Paquete:** `docs/pipelinek-coordinated-evolution/`
**Agente:** PK Producer Agent (responsable de `Rubentxu/pipeline-kotlin`)

## Comprobaciones requeridas por `coordination/BASELINE-PINS.md`

Cada agente debe obtener, registrar con fecha y comparar contra el package antes de
arrancar trabajo. Drift ⇒ `BLOCKED`.

| Señal | Valor esperado | Valor medido | OK? |
|---|---|---|---|
| `git rev-parse HEAD` (PK local) | HEAD limpio sobre `origin/main` | `65f97430accfbeec22b7b05157d57d05563c87d5` | ✓ |
| `git ls-remote origin refs/heads/main` (PK remoto) | mismo SHA que local | `65f97430accfbeec22b7b05157d57d05563c87d5` | ✓ |
| Último tag publicado (PK) | `v0.48.0-rc2` | `v0.48.0-rc2` (tag object `4c350231e06b39920dac2a0c0f9eb8eb007d35e3`) | ✓ |
| Peel del tag (commit) | debe ser ancestro de `origin/main` | `74c5331e2c242658f6b4c9e83f896ce5f8a4fe98`, ancestro confirmado | ✓ |
| Release Prerelease en GitHub | publicado | [v0.48.0-rc2](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc2) publicado 2026-10-10T13:34:54Z | ✓ |
| Contract SHA-256 (PK) | `0b14b85327303b76eeee4a6dc3f381619edf9a28b1b7cdaa894633b74106d26b` (de `CONTRACT_SHA256.txt`) | `0b14b85327303b76eeee4a6dc3f381619edf9a28b1b7cdaa894633b74106d26b` | ✓ |
| `INTERFACE_CONTRACT.md` en ambos paquetes | byte-idéntico | PK: `0b14b85…`; Fabric (no medido aquí — verificar al Pair Integrator) | parcial |

## Comandos ejecutados

```bash
git -C /home/rubentxu/Proyectos/kotlin/pipeline-kotlin rev-parse HEAD
# 65f97430accfbeec22b7b05157d57d05563c87d5

git -C /home/rubentxu/Proyectos/kotlin/pipeline-kotlin ls-remote origin refs/heads/main
# 65f97430accfbeec22b7b05157d57d05563c87d5

git -C /home/rubentxu/Proyectos/kotlin/pipeline-kotlin rev-parse v0.48.0-rc2
# 4c350231e06b39920dac2a0c0f9eb8eb007d35e3   (tag object)

git -C /home/rubentxu/Proyectos/kotlin/pipeline-kotlin rev-parse v0.48.0-rc2^{}
# 74c5331e2c242658f6b4c9e83f896ce5f8a4fe98   (commit)

git -C /home/rubentxu/Proyectos/kotlin/pipeline-kotlin ls-remote origin refs/tags/v0.48.0-rc2
# 4c350231e06b39920dac2a0c0f9eb8eb007d35e3

git -C /home/rubentxu/Proyectos/kotlin/pipeline-kotlin merge-base --is-ancestor 74c5331 origin/main
# 0 (ancestro confirmado)

sha256sum /home/rubentxu/Proyectos/kotlin/pipeline-kotlin/docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md
# 0b14b85327303b76eeee4a6dc3f381619edf9a28b1b7cdaa894633b74106d26b
```

## Drift respecto a `BASELINE-PINS.md` (medición 2026-10-10)

`BASELINE-PINS.md` registró PK `543e1cc5e61f6c92fe1c7ffbd8b513bbe90395b4` el 2026-10-10
con v0.48.0-rc1 como producto publicado. Esa medición quedó obsoleta por los commits
posteriores (incluido el bump a v0.48.0-rc2 + Prerelease publicado el 2026-10-10T13:34:54Z).
**No es drift** en el sentido de `BLOCKED` — es avance legítimo: el package se concibió
sobre `543e1cc5` y la release v0.48.0-rc2 llegó después. El agente PK documenta el
estado actual en este recibo; la siguiente revalidación medirá sobre `65f9743` y
mantendrá `BASELINE-PINS.md` alineado con la nueva realidad.

## Estado por rol

### PK (este recibo)

- HEAD local = `origin/main` = `65f97430`. **No hay drift técnico** en el lado PK.
- Tag `v0.48.0-rc2` publicado y ancestro de `origin/main`. **No hay drift** en releases.
- `INTERFACE_CONTRACT.md` SHA-256 coincide con `CONTRACT_SHA256.txt`. **No hay drift** en el
  contrato (CRIC-1 vigente).
- 7 commits entre el tag y HEAD (5 docs + 1 release + 1 feature doc) — todos metadata,
  ninguno cambia el binario ni la API publicada.

### Fabric (no medido aquí)

- Baseline registrada en `BASELINE-PINS.md`: `e6e4fd3e191dd483994af89418c93ecd5ffdb3e0`.
- El agente PK **no puede medir** el lado Fabric desde este workspace. La verificación
  cruzada del `INTERFACE_CONTRACT.md` byte-idéntico es trabajo del **Pair Integrator** o
  del **Fabric Consumer Agent** corriendo `verify_pair_gate.py --strict-remote` con ambos
  checkouts. `pair_gate_selftest.py` valida que el gate acepta igualdad exacta y bloquea
  discrepancias — esa ejecución se captura en WIP-4.

## Veredicto y consecuencia

- **M0 WIP-0: PASS**. El lado PK está alineado, el contrato CRIC-1 sigue vigente, la
  release `v0.48.0-rc2` está publicada y en `origin/main`.
- **No hay bloqueo**. Continuar a WIP-1 (medir ABI publicado).
- **Pendiente para el Pair Integrator**: ejecutar `verify_pair_gate.py --strict-remote`
  con ambos clones (PK y Fabric) para la admisión cruzada completa.

## Próximo paso

WIP-1: medir el ABI publicado PK (BCV dumps + `published-contract-exceptions.json` +
4 contratos publicados según `INITIATIVE_LPR_001.md` y `v2/pipeline-release/`). Salida
esperada: `docs/v2/07-uat/coordination-evolution/M0_ABI_INVENTORY.md`.
