# UAT Runbook delta

## Canonical command shape

Los nombres finales pueden adaptarse al Justfile.

```bash
just scenario EX-020
just scenario-compile EX-020
just scenario-real EX-020
just scenario-restart EX-060
just scenario-sandbox SEC-003
just examples
just compatibility
just plugin-fixture example-uppercase
just certify-step core.sh
just certify-plugin example-uppercase
```

## Evidence

Registrar:

- fixture path;
- Git SHA;
- argv;
- fidelity;
- exit code;
- fresh JUnit XML;
- diagnostics path;
- certification receipt.

`BUILD SUCCESSFUL` no es suficiente si el escenario debía ejecutarse.

## Golden updates

Sólo explícitas:

```bash
just scenario-update EX-020
git diff -- examples/20-nested-env/expected
```

CI nunca auto-actualiza goldens.

## Failure triage

1. compile -> scripting/source diagnostic;
2. IR -> builder/compiler contract;
3. registry/capability -> StepDefinition/admission;
4. handler -> typed result/events;
5. replay -> fingerprints/journal/session;
6. sandbox-only -> process/container policy;
7. online-only -> upstream/network classification.

## Quarantine

Requiere:

- issue/debt ID;
- owner;
- reason;
- expected failing assertion;
- milestone owner.

Quarantine obligatorio de LFC-2 => milestone OPEN.
