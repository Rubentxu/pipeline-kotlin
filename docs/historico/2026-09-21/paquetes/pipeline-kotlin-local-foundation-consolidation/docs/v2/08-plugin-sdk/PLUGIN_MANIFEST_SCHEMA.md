# Plugin manifest schema — v1 design

```yaml
apiVersion: pipeline.dev/v1
kind: PipelinePlugin
metadata:
  id: pipeline.junit
  name: JUnit
  version: 1.4.0
  description: Publish JUnit XML test results
spec:
  pluginApi: 1
  runtimeCompatibility: ">=1.0 <2.0"
  providerClass: dev.rubentxu.pipeline.junit.JUnitPluginProvider
  extensions:
    - kind: Step
      id: junit.junit
      descriptor: META-INF/pipeline/extensions/junit.junit.json
  permissions:
    workspace: [read]
    network: []
    credentials: []
  jenkins:
    plugins: [junit]
```

## Rules

- manifest is readable without executing plugin code;
- version/API range is validated before classloading provider;
- descriptor IDs are unique in the resolved plugin set;
- permissions/capabilities are declarative and compared with generated handler requirements;
- digest verification happens before loading.
