# External grounding sources

These sources informed the architecture. They are references, not implementation dependencies.

## CloudEvents

- CloudEvents Specification: https://github.com/cloudevents/spec/blob/main/cloudevents/spec.md
  - `source + id` uniqueness/deduplication;
  - `source` is a URI-reference;
  - `subject` identifies the object within the source context;
  - transport/delivery semantics are outside the core event format.

## Cedar

- Authorization model: https://docs.cedarpolicy.com/auth/authorization.html
- Schema: https://docs.cedarpolicy.com/schema/schema.html
- Context best practice: https://docs.cedarpolicy.com/bestpractices/bp-using-the-context.html
  - principal/action/resource/context request model;
  - schemas validate entity/action/context shapes;
  - request-specific context should not replace stable resource attributes.

## NATS JetStream — candidate only

- Consumers: https://github.com/nats-io/nats.docs/blob/master/nats-concepts/jetstream/consumers.md
- JetStream overview/replay: https://github.com/nats-io/nats.docs/blob/master/nats-concepts/jetstream/README.md
  - durable consumers can retain state;
  - stream messages can be replayed from sequence/time;
  - at-least-once delivery means consumers must be idempotent/dedupe semantically.

## Jenkins

- Pipeline Graph View: https://plugins.jenkins.io/pipeline-graph-view/
  - stages/parallel children and real-time logs make live event consumption relevant for future M6 UI.

## Linux isolation

- cgroup v2: https://docs.kernel.org/admin-guide/cgroup-v2.html
  - CPU weights/limits and memory high/max provide a future OS-level resource boundary for detached observers.
