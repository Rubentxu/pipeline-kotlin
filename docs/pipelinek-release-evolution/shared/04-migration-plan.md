# Migration Plan — Release Protocol v1 -> v2

## M0 — Freeze de publicación, no de desarrollo

Temporalmente bloquear:

- nuevas publicaciones RC/GA con el promoter transformador actual.

NO bloquear:

- main;
- S1/S2 u otros trabajos independientes;
- commits/features;
- tests quirúrgicos.

## M1 — Erratum 0.43.0

Registrar factual y explícitamente:

```text
v0.43.0 asset integrity: PASS
v0.43.0 distribution identity: FAIL
contained runtime identity: 0.43.0-rc1
```

No mover tags ni reemplazar assets históricos.

## M2 — Contrato v2

Aplicar primero documentos/AGENTS en ambos repos para que los agentes no sigan implementando el protocolo anterior.

## M3 — Harness identity gate

Antes de producir la siguiente estable, el harness debe detectar reproduciblemente el defecto de 0.43.0.

Esto es el canary del nuevo gate.

## M4 — Product target-version candidate

Modificar upstream para construir una candidata cuyo producto ya sea `0.43.1`/`0.44.0`, independientemente de su estado Candidate.

## M5 — 0.43.1 corrective train

Crear candidate material `0.43.1` sin features S1 nuevas si se quiere reparar la stable anterior con riesgo mínimo.

Harness certifica y promociona exactos bytes.

## M6 — Queue coalescing

Activar latest-wins antes de aumentar frecuencia de candidates.

## M7 — Channels hardening

Después del protocolo base:

- mise GitHub backend;
- asdf plugin independiente;
- manual installer;
- upgrade/rollback/path poisoning.

## M8 — Reanudar 0.44 publication

Main puede haber seguido evolucionando durante M1..M7. Al volver a publicar, materializar `latest` del train 0.44 como candidata v2.
