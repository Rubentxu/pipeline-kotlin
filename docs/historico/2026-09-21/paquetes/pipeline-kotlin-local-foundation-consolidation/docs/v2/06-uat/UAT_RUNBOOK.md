# UAT runbook

## Standard execution

Each UAT must be runnable through one stable Gradle task or a repository script that ultimately invokes the public CLI where possible.

Suggested convention:

```bash
./gradlew :v2:uat:test --tests '*UatDsl*'
./gradlew :v2:uat:test --tests '*UatRuntime*'
./gradlew :v2:uat:test --tests '*UatCredentials*'
./gradlew :v2:uat:test --tests '*UatOutput*'
```

Exact module/task names should match the existing repo; the grouping is normative, not these literal paths.

## Black-box CLI fixture

```bash
TMP=$(mktemp -d)
cp -R fixtures/projects/gradle-basic "$TMP/project"
cd "$TMP/project"
pipeline validate --locked
pipeline run --ci --report result.json
pipeline logs "$(jq -r .runId result.json)"
```

## Failure diagnostics

On failure retain:

- CLI stdout/stderr;
- run directory path;
- event/journal/output store metadata;
- process tree/cancellation diagnostic if relevant;
- IR/lock digest;
- platform/version metadata.

Never dump raw credential material into the retained bundle.

## Performance UAT

Record CPU, RAM, OS/kernel, filesystem and runtime version. Compare against a stored baseline envelope; do not fail solely on noisy wall-clock differences without confidence bounds.

## Release installation UAT

Use disposable clean VMs/containers/runners. Do not pass because the developer machine already has a JDK or previous `pipeline` installation.
