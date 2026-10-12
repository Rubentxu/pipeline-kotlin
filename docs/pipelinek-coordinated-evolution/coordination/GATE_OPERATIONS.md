# Operación del gate: requisitos y ejecución fail-closed

## Uso

Ambos ZIP incluyen los mismos `coordination/INTERFACE_CONTRACT.md`, `PAIR_RELEASE_FLOW.md`, `MILESTONE_MATRIX.md`, `gate-policy.json`, `verify_pair_gate.py`. El hash SHA-256 del contrato **debe** ser idéntico. Un único Integrator comprueba ambos workspaces y el recibo del milestone, nunca da `PASS` a un único repositorio aislado.

1. Importar cada documentación en el repo correcto; mantener estos ficheros idénticos. Añadir punteros a ROADMAP del producto, no crear otra autoridad de producto.
2. Congelar `CRIC-1` por hash y lanzar trabajo paralelo en branches/worktrees. Publicar artefacto provisional inmutable para que Fabric compile sin depender de un checkout mutable o `mavenLocal`.
3. Para candidate: ejecutar testkit que consume PipelineK del artifact candidato fijado por SHA. Rechazar `NOT_MEASURED`.
4. Para publish: publicar PK primero **si hubo cambio** y verificar su artifact desde registry remoto; repetir test consumidor Fabric con PK ya publicado; publicar Fabric. Revalidar juntos contra el par publicado y guardar logs de pruebas con hashes.
5. Crear `PAIR_RECEIPT.json` desde la plantilla y ejecutar verificador `--strict-remote` con dos repositorios checkouteados. Guardar salida del verificador como evidencia de gate; la existencia de un JSON **no** equivale a haber ejecutado CI.
6. Solo entonces autorizar el siguiente hito y reflejar `PAIR_CERTIFIED` en la autoridad de la iniciativa, con referencia a ese recibo immutable.

## Ejemplo

```bash
python3 coordination/verify_pair_gate.py \
  --receipt /ruta/workspace/receipts/M1/PAIR_RECEIPT.json \
  --pk-repo /ruta/workspace/pipeline-kotlin \
  --fabric-repo /ruta/workspace/pipelinek-fabric \
  --pk-package /ruta/workspace/pipelinek-coordinated-evolution \
  --fabric-package /ruta/workspace/fabric-coordinated-evolution \
  --strict-remote
```

La verificación estricta ejecuta `git ls-remote` en los `origin` de ambos repos y comprueba el tag remoto, el SHA exacto del commit, la pertenencia del tag a la rama `origin/main` local **sin permitir divergencia con el SHA remoto**, el fingerprint del contrato, los artifacts descargados y los hashes de evidencias de las pruebas exigidas. Requiere tener los remotes sincronizados; falla si un ref local está desactualizado.

## Límites del bloqueo

El script por sí solo no puede impedir un `git push` manual a GitHub. Para bloqueo a nivel de rama se requieren reglas de protección/rulesets y status externo de CI de integración, o un servidor Git con hook de recepción controlado. Este paquete implementa el gate de *admisión* y define la configuración requerida de protección remota; no declara reglas de GitHub ya instaladas ni releases ya publicados.

## La evidencia es un dato, no una aseveración

Cada `evidence[].path` debe apuntar a un fichero real de log/salida generado por una prueba y enlazado por su digest. No aceptar manualmente `PASS` sin salida de test, oráculo observacional e inspección, ni datos inventados. `PAIR_CERTIFIED` sólo si todos los required IDs están en evidencias sin SKIP.

### Encadenar hitos

Para M1..M7 añadir `--previous-receipt /ruta/workspace/receipts/M(n-1)/PAIR_RECEIPT.json` al comando. El JSON nuevo debe declarar `previous_pair: {milestone, pair_id, receipt_sha256}` que referencie exactamente los bytes de ese recibo. Un intento de saltarse un hito falla cerrado.

**Cada M0–M7 publica un Fabric nuevo.** Si PK permanece sin cambios, se prueba la versión publicada anterior y se reutiliza su tag/SHA exactamente. Si un contrato cambia entre hitos, exigir `contract_migration` aprobado y un log `GATE-CONTRACT-MIGRATION`; no confundir cambio menor con ABI breaking.

### Autotest no destructivo de la admisión

```bash
python3 coordination/pair_gate_selftest.py \
  --pk-package /ruta/workspace/pipelinek-coordinated-evolution \
  --fabric-package /ruta/workspace/fabric-coordinated-evolution
```

Esta suite verifica que el gate acepta la igualdad exacta y **bloquea** discrepancias entre repos o recibos incompletos; **no** simula un release real ni pretende sustituir los UAT/AAT del par.
