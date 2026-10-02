# WU-092 `core.input` — Informe de exploración (RP6-B)

> cycle SDDK: `p-1f3622e11c093341/rp6b-input`
> spec resultante: `docs/v2/07-uat/SPEC_WU092_INPUT.md` (`art-9b0b8414079a-9f9e626a`)

## 1. Qué se buscó y qué se encontró

La pregunta de exploración no era "¿cómo se implementa `input`?" sino **"¿existe ya
una forma de decidir, o de parar, dentro de este runtime?"** La respuesta
determina si la WU es una pieza nueva o un hueco Tapado.

| Superficie investigada | Hallazgo | Consecuencia para la WU |
| --- | --- | --- |
| `OperationStatus` (durable) | `PENDING, RUNNING, SUCCEEDED, FAILED, ABORTED, DIVERGENT, LOST, FAILED_TIMEOUT`. **Ningún estado de suspensión.** | El journal no tiene un estado "run parked": la espera debe vivir como suspensión del handler (spec §3.2). |
| `RecoveryPolicy` | Sólo `None` y `ExternalSubprocess`, con reattach/poll desde un control dir. | Ese es el ** precedents** exacto: la decisión de `input` se reconcilia desde el control dir, sin estado nuevo. |
| `WhenDirectiveDefinition` (S1-C) | Gate **machine-decidable** en `BEFORE_STAGE`; "the only policy that can stop the stage". | `input` **no** compite con `when`: aquélla decide antes de empezar, ésta decide dentro de la ejecución. |
| `PostConditionSelected` / `StageSkipped` (S2-A/B) | Selección de bloques y stage omitida por veredicto. | Igual: sin pausa, sin decisión humana. |
| Precedente `core.lock` (RP6-A) | Capacidad estrecha por handler, espera cooperativa acotada, `HANDLER_CONTINUATION` para cuerpo condicional, cruce puro con `EXECUTION_BUDGET_CAPABILITY`. | El patrón se transfiere casi entero; es lo que hace la WU barata en riesgo y cara en valor. |

## 2. El hueco real

Existe la *decisión automática* (gate, directiva) y existe la *coordinación
concurrente* (lock). **No existe la decisión humana dentro de un run** en un
runner que es, por diseño, headless: sin executor web, sin UI, sin base de
usuarios. Ése es el hueco que `core.input` cierra, y es la pieza que hace que un
pipeline de despliegue pueda exigir "aprobado por una persona" sin salirse del
modelo local-first.

## 3. La decisión que la exploración cerró

El mecanismo de respuesta se decidió al explorar, no después:

```text
<controlDirRoot>/inputs/<opId>/request.json    escrito por el handler
<controlDirRoot>/inputs/<opId>/response.json   escrito por quien responde
```

Se descartó escribir en el journal desde fuera (segundo escritor sobre la verdad
del engine) y se descartó un daemon (infraestructura nueva para un paso que no la
necesita). Se siguió el precedente de `RecoveryPolicy.ExternalSubprocess`, que ya
resuelve "reconciliar el estado de una operación desde el control dir" para el
shell: `input` reusa ese canal a través de una capability, sin tocar el spine.

## 4. Riesgos identificados antes de implementar

| Riesgo | Mitigación decidida en la spec |
| --- | --- |
| Fichero a medio escribir leído como respuesta | No se consume: se ignora y se sigue esperando (§3.4) |
| Dos respuestas simultáneas | `CREATE_NEW` atómico; la segunda recibe `AlreadyAnswered` tipado |
| Proceso muere esperando | `LOST` → `RERUN` por la matriz existente; al re-ejecutar relee la respuesta y converge |
| Espera que se cuelga para siempre | Acotada por `EXECUTION_BUDGET_CAPABILITY`, igual que lock |
| `submitter` leído como autorización | D5 lo dice en voz alta: es atribución, y la autorización es de S1-C |
| Re-pregunta en cada re-run | `MEMOIZED` (D3): la decisión es un hecho durable |

## 5. Qué NO se exploró (y por qué es correcto)

- **Servidor/agente para responder**: sería otro evolutivo; la spec lo deja
  explícitamente fuera (§8).
- **Autorización de `submitter`**: pertenece al modelo de identidad de S1-C, no a
  este paso. La exploración confirmó que no existe aquí ninguna base de usuarios
  sobre la que construirla honestamente.
