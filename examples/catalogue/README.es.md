# El catalogo de ejemplos

<sub>GENERADO por el arnes de releasing desde el catalogo de escenarios. No lo edites a mano: la siguiente generacion lo vuelve a escribir.</sub>

Todos los ejemplos de una capacidad de PipelineK, con el pipeline que la muestra y un README que la explica. El catalogo vive en el arnes de releasing, que es quien lo ejecuta; esta es la copia que viaja con el producto, para que un lector vea que hace una capacidad sin instalar nada.

Los ejemplos vienen en dos grupos. Las **features** son los ejemplos del motor, por familia. Las **regressions** son los escenarios con los que el arnes de releasing certifica una candidata.

| Ejemplo | Que cubre | Grupo |
|---|---|---|
| [`scripted-control-flow`](features/control-flow/scripted-control-flow/README.es.md) | que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle | `control-flow` |
| [`workflow-control`](features/control-flow/workflow-control/README.es.md) | que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle | `control-flow` |
| [`writefile-in-catcherror`](features/control-flow/writefile-in-catcherror/README.es.md) | que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle | `control-flow` |
| [`writefile-in-warnerror`](features/control-flow/writefile-in-warnerror/README.es.md) | que rama de una bifurcacion se ejecuta, y cuantas veces corre el cuerpo de un bucle | `control-flow` |
| [`cancel-dead-run`](features/durable/cancel-dead-run/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`durable-replay`](features/durable/durable-replay/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`milestone`](features/durable/milestone/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`platform-queries`](features/durable/platform-queries/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`recover-refuses-a-half-finished-run`](features/durable/recover-refuses-a-half-finished-run/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-compensation`](features/durable/saga-compensation/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-crash-compensation`](features/durable/saga-crash-compensation/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-crash-effect`](features/durable/saga-crash-effect/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-needs-intervention`](features/durable/saga-needs-intervention/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-reconcile`](features/durable/saga-reconcile/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-success`](features/durable/saga-success/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`saga-two-same-kind`](features/durable/saga-two-same-kind/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`stash-unstash`](features/durable/stash-unstash/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`wait-until`](features/durable/wait-until/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`wait-until-deadline`](features/durable/wait-until-deadline/README.es.md) | el diario: que deja una corrida cuando la matan, y como se reanuda, cancela o recupera | `durable` |
| [`external-reader-resumes`](features/events/external-reader-resumes/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`failure-stage`](features/events/failure-stage/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`follow-filtered`](features/events/follow-filtered/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`live-spans-without-a-collector`](features/events/live-spans-without-a-collector/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`observer-death-does-not-kill-the-producer`](features/events/observer-death-does-not-kill-the-producer/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`run-state`](features/events/run-state/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`step-verdict`](features/events/step-verdict/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`telemetry-export`](features/events/telemetry-export/README.es.md) | la via de eventos: que le cuenta una corrida a un consumidor, y en que orden | `events` |
| [`credential-store-and-unreadable-plugin`](features/negative/credential-store-and-unreadable-plugin/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`credential-store-tampered`](features/negative/credential-store-tampered/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`credential-store-without-passphrase`](features/negative/credential-store-without-passphrase/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`error`](features/negative/error/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`error-handling`](features/negative/error-handling/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`events-cursor-beyond-tail`](features/negative/events-cursor-beyond-tail/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`events-no-event-history`](features/negative/events-no-event-history/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`events-unknown-run`](features/negative/events-unknown-run/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`no-pipeline-produced`](features/negative/no-pipeline-produced/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`non-canonical-step-refusal`](features/negative/non-canonical-step-refusal/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-artifact-not-measurable`](features/negative/plugin-artifact-not-measurable/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-capability-lies`](features/negative/plugin-capability-lies/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-capability-omitted`](features/negative/plugin-capability-omitted/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-directive-lies`](features/negative/plugin-directive-lies/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-duplicate-identity`](features/negative/plugin-duplicate-identity/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-event-lies`](features/negative/plugin-event-lies/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-manifest-lies`](features/negative/plugin-manifest-lies/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`plugin-trust-digest-mismatch`](features/negative/plugin-trust-digest-mismatch/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`refused-run-is-still-answerable`](features/negative/refused-run-is-still-answerable/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`step-failure`](features/negative/step-failure/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`unknown-directive-key`](features/negative/unknown-directive-key/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`workspace-symlink-escape`](features/negative/workspace-symlink-escape/README.es.md) | las negativas: a que se niega el motor, y que dice cuando se niega | `negative` |
| [`artifact-query-bridge`](features/output/artifact-query-bridge/README.es.md) | el plano de salida: que produjo un paso, y como se vuelve a leer | `output` |
| [`live-process-output`](features/output/live-process-output/README.es.md) | el plano de salida: que produjo un paso, y como se vuelve a leer | `output` |
| [`publish-html`](features/output/publish-html/README.es.md) | el plano de salida: que produjo un paso, y como se vuelve a leer | `output` |
| [`read-only-verb-streams`](features/output/read-only-verb-streams/README.es.md) | el plano de salida: que produjo un paso, y como se vuelve a leer | `output` |
| [`readfile`](features/output/readfile/README.es.md) | el plano de salida: que produjo un paso, y como se vuelve a leer | `output` |
| [`utilities`](features/output/utilities/README.es.md) | el plano de salida: que produjo un paso, y como se vuelve a leer | `output` |
| [`body-slots`](features/plugins/body-slots/README.es.md) | los pasos que aporta un plugin, y las capacidades que declara | `plugins` |
| [`lock-directive`](features/plugins/lock-directive/README.es.md) | los pasos que aporta un plugin, y las capacidades que declara | `plugins` |
| [`repeat-block`](features/plugins/repeat-block/README.es.md) | los pasos que aporta un plugin, y las capacidades que declara | `plugins` |
| [`trusted-plugin`](features/plugins/trusted-plugin/README.es.md) | los pasos que aporta un plugin, y las capacidades que declara | `plugins` |
| [`uppercase`](features/plugins/uppercase/README.es.md) | los pasos que aporta un plugin, y las capacidades que declara | `plugins` |
| [`uppercase-measured`](features/plugins/uppercase-measured/README.es.md) | los pasos que aporta un plugin, y las capacidades que declara | `plugins` |
| [`child-env-has-no-store-passphrase`](features/security/child-env-has-no-store-passphrase/README.es.md) | la frontera de confianza: que bytes de un plugin se pueden ejecutar | `security` |
| [`credentials-bindings`](features/security/credentials-bindings/README.es.md) | la frontera de confianza: que bytes de un plugin se pueden ejecutar | `security` |
| [`zip-slip-defense`](features/security/zip-slip-defense/README.es.md) | la frontera de confianza: que bytes de un plugin se pueden ejecutar | `security` |
| [`cli-self-report`](features/smoke/cli-self-report/README.es.md) | el camino mas corto por el motor: un pipeline que arranca, corre y termina | `smoke` |
| [`environment`](features/smoke/environment/README.es.md) | el camino mas corto por el motor: un pipeline que arranca, corre y termina | `smoke` |
| [`smoke-e2e`](features/smoke/smoke-e2e/README.es.md) | el camino mas corto por el motor: un pipeline que arranca, corre y termina | `smoke` |
| [`cleanWs`](features/steps/cleanWs/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`env-model-applied-once`](features/steps/env-model-applied-once/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`find-files`](features/steps/find-files/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`glob-ant-alternation`](features/steps/glob-ant-alternation/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`sleep`](features/steps/sleep/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`withEnv-pipeline`](features/steps/withEnv-pipeline/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`workspace-coherence`](features/steps/workspace-coherence/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`workspace-helpers`](features/steps/workspace-helpers/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`writeFile`](features/steps/writeFile/README.es.md) | los pasos del DSL, uno a uno, y que garantiza cada uno | `steps` |
| [`branch-isolation`](regressions/smoke/branch-isolation/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`broken-test`](regressions/smoke/broken-test/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`dir-nested`](regressions/smoke/dir-nested/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`dir-restore`](regressions/smoke/dir-restore/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`dsl-invalid`](regressions/smoke/dsl-invalid/README.es.md) | un ejemplo que el catalogo declara sin categoria | `smoke` |
| [`egress-blocked`](regressions/smoke/egress-blocked/README.es.md) | que pasa cuando un paso intenta llegar a la red | `smoke` |
| [`failure`](regressions/smoke/failure/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`no-secret-leak`](regressions/smoke/no-secret-leak/README.es.md) | las fronteras que una corrida no puede cruzar | `smoke` |
| [`rust-build-neg`](regressions/smoke/rust-build-neg/README.es.md) | la vida de una corrida: como arranca, como acaba y que concluye | `smoke` |
| [`rust-build-test`](regressions/smoke/rust-build-test/README.es.md) | la vida de una corrida: como arranca, como acaba y que concluye | `smoke` |
| [`success`](regressions/smoke/success/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`workspace-contract`](regressions/smoke/workspace-contract/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`workspace-contract-matrix`](regressions/smoke/workspace-contract-matrix/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`workspace-dir`](regressions/smoke/workspace-dir/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `smoke` |
| [`branch-failure-reported`](regressions/canary/branch-failure-reported/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `canary` |
| [`catcherror-handled`](regressions/canary/catcherror-handled/README.es.md) | la vida de una corrida: como arranca, como acaba y que concluye | `canary` |
| [`dir-nested-deep`](regressions/canary/dir-nested-deep/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `canary` |
| [`findfiles-readonly`](regressions/canary/findfiles-readonly/README.es.md) | leer y escribir ficheros, y lo que queda para releer | `canary` |
| [`read-only-root`](regressions/canary/read-only-root/README.es.md) | las fronteras que una corrida no puede cruzar | `canary` |
| [`readfile-step`](regressions/canary/readfile-step/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `canary` |
| [`repeat-block`](regressions/canary/repeat-block/README.es.md) | pasos que aporta un plugin, y las capacidades que declara | `canary` |
| [`saga-settled`](regressions/canary/saga-settled/README.es.md) | pasos que aporta un plugin, y las capacidades que declara | `canary` |
| [`stderr-channel`](regressions/canary/stderr-channel/README.es.md) | la vida de una corrida: como arranca, como acaba y que concluye | `canary` |
| [`stdout-channel`](regressions/canary/stdout-channel/README.es.md) | la vida de una corrida: como arranca, como acaba y que concluye | `canary` |
| [`toolchain-inventory`](regressions/canary/toolchain-inventory/README.es.md) | la vida de una corrida: como arranca, como acaba y que concluye | `canary` |
| [`workspace-clean`](regressions/canary/workspace-clean/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `canary` |
| [`writefile-step`](regressions/canary/writefile-step/README.es.md) | el directorio de trabajo en el que corre una corrida, y que sobrevive de una etapa a otra | `canary` |
