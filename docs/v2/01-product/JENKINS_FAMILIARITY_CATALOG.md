# Jenkins Pipeline Step Catalog — Canonical Reference for v2 "Jenkins Familiarity" Rules

**Method & provenance.** Every signature below was extracted verbatim on **2026-08-26** from the jenkins.io Pipeline Steps Reference (`https://www.jenkins.io/doc/pipeline/steps/<plugin>/`, generated from current plugin releases), cross-checked against `jenkinsci/*` GitHub source trees for step *ownership*, the Declarative syntax reference (`jenkins.io/doc/book/pipeline/syntax/`), and plugins.jenkins.io for install counts and current versions. No signature is from memory. Install counts come from the plugins.jenkins.io installation-stats badge (reflects instances reporting to the update center; bundled plugins cluster at ~190–200k ≈ "installed everywhere").

**Defaults convention (one global rule, not per-row invention):** the steps reference marks params `(optional)` but rarely prints defaults. Where a default is documented on the page it is shown as `= default`. Where not shown, Jenkins data-binding semantics apply: unmarked optional `boolean` ⇒ `false`, unmarked optional `String`/`int`/object ⇒ `null`/unset. Anything not marked "default documented" follows this rule.

---

## 0. Corrections vs. common assumptions (verified against source code)

| Common claim | Reality (evidence) |
|---|---|
| `build` in parameterized-trigger | `build` is **pipeline-build-step** (`BuildTriggerStep`). parameterized-trigger (54k installs) only *contributes parameter types* to `build`'s `parameters` list via an extension point; the step itself ships in pipeline-build-step (191k installs, bundled). |
| `script` in workflow-durable-task-step | `script` is **pipeline-model-definition** (`ScriptStep.java` confirmed in repo tree; listed on its steps page). It's the Declarative escape hatch — meaningless in pure scripted pipelines. |
| `node` in workflow-cps | `node` = `ExecutorStep` in **workflow-durable-task-step**. workflow-cps contains only `load` and `parallel` (repo tree: LoadStep.java, ParallelStep.java). |
| `dir` in workflow-basic-steps | `dir` = `PushdStep` in **workflow-durable-task-step** (a `PushdStep.java` also exists in the basic-steps repo — historical duplication — but the docs page attributes `dir` to durable-task-step). |
| `properties` in workflow-cps | `properties` = `JobPropertyStep` in **workflow-multibranch** (repo tree + steps page confirm). |

Also verified: `archiveArtifacts` **is** in workflow-basic-steps (`ArtifactArchiverStep.java`) but is documented on the `/steps/core/` page, not the basic-steps page; `stage` (scripted) is **pipeline-stage-step**; `timeout`/`retry`/`sleep` are workflow-basic-steps (`TimeoutStep`, `RetryStep`, `SleepStep`), *not* durable-task-step.

---

## 1. Catalog by plugin

### 1.1 workflow-basic-steps — v1098.v808b_fd7f8cf4 (193k installs)
Source: `https://www.jenkins.io/doc/pipeline/steps/workflow-basic-steps/` and `https://www.jenkins.io/doc/pipeline/steps/core/` (archiveArtifacts)

| step | signature | notes | source |
|---|---|---|---|
| `echo` | `echo(message: String)` | required: message. F1 core | basic-steps page |
| `error` | `error(message: String)` | required: message. Fails build. F1 | basic-steps page |
| `sleep` | `sleep(time: int, unit: TimeUnit = SECONDS)` | unit values: NANOSECONDS…DAYS. F1 (time), F3 (unit) | basic-steps page |
| `retry` | `retry(count: int, conditions: List<RetryCondition>)` | conditions nested choice: `agent`, `kubernetesAgent`, `nonresumable` — F3, infra-retry only | basic-steps page |
| `timeout` | `timeout(time: int, unit: TimeUnit = MINUTES, activity: boolean = false)` | `activity` = no-log-activity timeout vs absolute. F1 (time/unit), F2 (activity) | basic-steps page |
| `writeFile` | `writeFile(file: String, text: String, encoding: String)` | encoding: platform default; `"Base64"` decodes binary. F1 (file,text), F2 (encoding) | basic-steps page |
| `readFile` | `readFile(file: String, encoding: String)` | same encoding semantics. F1 (file), F2 (encoding) | basic-steps page |
| `fileExists` | `fileExists(file: String)` | returns boolean; abs or relative to cwd. F1 | basic-steps page |
| `isUnix` | `isUnix()` | no params; returns boolean. F1 | basic-steps page |
| `pwd` | `pwd(tmp: boolean = false)` | F1; `tmp` F3 | basic-steps page |
| `withEnv` | `withEnv(overrides: List<String>)` | each `"VAR=value"`; `PATH+X=/p` prepends. F1 | basic-steps page |
| `catchError` | `catchError(buildResult: String, stageResult: String, message: String, catchInterruptions: boolean = true)` | result can only worsen; F1 (bare catchError), F2 (buildResult/stageResult), F3 (catchInterruptions) | basic-steps page |
| `unstable` | `unstable(message: String)` | sets stage result UNSTABLE. F2 | basic-steps page |
| `warnError` | `warnError(message: String, catchInterruptions: boolean = true)` | ≡ `catchError(message:, buildResult:'UNSTABLE', stageResult:'UNSTABLE')`. F2 | basic-steps page |
| `deleteDir` | `deleteDir()` | no params. F1 | basic-steps page |
| `archiveArtifacts` | `archiveArtifacts(artifacts: String, allowEmptyArchive: boolean, caseSensitive: boolean, defaultExcludes: boolean, excludes: String, fingerprint: boolean, followSymlinks: boolean, onlyIfSuccessful: boolean)` | only `artifacts` required. F1 (artifacts), F2 (fingerprint, allowEmptyArchive, excludes), F3 (rest) | core page |
| `stash` / `unstash` | `stash(name: String, allowEmpty: boolean = false, excludes: String, includes: String = '**', useDefaultExcludes: boolean = true)`; `unstash(name: String)` | F2 | basic-steps page |
| `tool` | `tool(name: String, type: String)` | F2 | basic-steps page |
| `mail` | `mail(subject: String, body: String, bcc, cc, charset = UTF-8, from, mimeType = text/plain, replyTo, to)` | basic mail; most people use emailext instead. F3 | basic-steps page |
| `waitUntil` | `waitUntil(initialRecurrencePeriod: long = 250, quiet: boolean = false)` | polls closure until true. F2 | basic-steps page |
| `step` / `wrap` | `step($class: String, …)`; `wrap($class: String, …)` | generic bridges to freestyle steps/wrappers. F2 | basic-steps page |

### 1.2 workflow-durable-task-step — v1479.v56e587f413a_7 (194k installs, bundled)
Source: `https://www.jenkins.io/doc/pipeline/steps/workflow-durable-task-step/`

| step | signature | notes | source |
|---|---|---|---|
| `sh` | `sh(script: String, encoding: String, label: String, returnStatus: boolean, returnStdout: boolean)` | only `script` required. Full deep-dive in §3. F1 | same page |
| `bat` | `bat(script: String, encoding: String, label: String, returnStatus: boolean, returnStdout: boolean)` | use `@` prefix to suppress echo with returnStdout. F1 on Windows | same page |
| `powershell` | `powershell(script: String, encoding, label, returnStatus, returnStdout)` | Windows PowerShell 5.x. F2 | same page |
| `pwsh` | `pwsh(script: String, encoding, label, returnStatus, returnStdout)` | PowerShell Core 6+. F2 | same page |
| `node` | `node(label: String)` | label = label expression (`linux && 64bit`, `!expression`, parens). Blank ⇒ any executor. F1 | same page |
| `ws` | `ws(dir: String)` | explicit workspace lock, relative to agent root or absolute. F2 | same page |
| `dir` | `dir(path: String)` | relative to current workspace cwd. F1 | same page |

### 1.3 workflow-cps + pipeline-stage-step + workflow-multibranch (pipeline fundamentals)
Sources: `…/steps/workflow-cps/`, `…/steps/pipeline-stage-step/`, `…/steps/workflow-multibranch/`

| step | plugin | signature | notes |
|---|---|---|---|
| `parallel` | workflow-cps (4370.v…) | `parallel(Map<String,Closure>)` — special form, no step params | F1 in scripted; declarative `parallel{}` keyword is model-definition |
| `load` | workflow-cps | `load(path: String)` | evaluate Groovy file, returns `this`. F3 |
| `stage` (scripted) | pipeline-stage-step (345.v…) | `stage(name: String, concurrency: int)` | `concurrency` deprecated legacy. Declarative `stage` is a directive, not this step. F1 |
| `properties` | workflow-multibranch (841.v…) | `properties(properties: List<JobProperty>)` | one optional list param; nested choices include `buildDiscarder(logRotator(…))`, `disableConcurrentBuild()`, `gitHubProjectProperty`, etc. F2 |

Declarative-only constructs (pipeline-model-definition 2.2293.v6e7193cec599, 189k installs): `pipeline`, `agent`, `stages`, `steps`, `post`, `environment`, `options`, `parameters`, `triggers`, `tools`, `when`, `matrix`, `script` — these are **directives/keywords of the Declarative interpreter**, not steps; only `script` (run arbitrary pipeline script, no params), `validateDeclarativePipeline(path: String)`, and `envVarsForTool(toolId: String, toolVersion: String)` appear as actual steps on its steps page. Source: `…/steps/pipeline-model-definition/` + syntax page.

### 1.4 pipeline-input-step — v560.v56198a_642157 (192k installs)
Source: `https://www.jenkins.io/doc/pipeline/steps/pipeline-input-step/`

| step | signature | notes | source |
|---|---|---|---|
| `input` | `input(message: String, cancel: String, id: String, ok: String, parameters: List<ParameterDefinition>, submitter: String, submitterParameter: String)` | only `message` required. **No `result` param exists** — the step's return value *is* the result (single param ⇒ its value; multiple ⇒ map). `cancel` (custom abort-button text) is a recent addition. F1 (message, ok, submitter), F2 (id, submitterParameter, parameters), F3 (cancel) |

### 1.5 git plugin — v5.10.1 (197k installs) + workflow-scm-step (200k, bundled)
Sources: `…/steps/git/`, `…/steps/workflow-scm-step/`

| step | signature | notes | source |
|---|---|---|---|
| `git` | `git(url: String, branch: String = 'master', changelog: boolean = true, credentialsId: String = <empty>, poll: boolean = true)` | exactly 5 params, no more — advanced checkouts require `checkout`. branch must be local name (no `origin/`, tags, SHAs). F1 (url, branch, credentialsId), F2 (changelog, poll) |
| `checkout` | `checkout(scm: SCM)` | one required param; `scm` is a nested choice over every installed SCM (`scmGit(...)`, `git`, `svn`, `github`, plus branch-source SCMs). The dominant real-world form is `checkout(scm)` inside multibranch pipelines (implicit `scm` variable). F1 |

### 1.6 credentials-binding — v728.v902a_273b_8947 (198k installs)
Source: `…/steps/credentials-binding/`

| step / binding | signature | notes |
|---|---|---|
| `withCredentials` | `withCredentials(bindings: List<CredentialsBinding>)` | only param. Bindings shipped by the plugin itself: |
| `string` | `string(credentialsId: String, variable: String)` | secret text. F1 |
| `usernamePassword` | `usernamePassword(credentialsId: String, usernameVariable: String, passwordVariable: String)` | F1 |
| `sshUserPrivateKey` | `sshUserPrivateKey(credentialsId: String, keyFileVariable: String, passphraseVariable: String, usernameVariable: String)` | `keyFileVariable` required; last two optional. F1 |
| `file` | `file(credentialsId: String, variable: String)` | secret file → temp path. F2 |
| `certificate` | `certificate(keystoreVariable: String, credentialsId: String, aliasVariable: String, passwordVariable: String)` | F3 |
| `zip` | `zip(variable: String, credentialsId: String)` | F3 |
| `usernameColonPassword` | `usernameColonPassword(variable: String, credentialsId: String)` | F3 |
| (contributed) | `gitUsernamePassword`, `gitlabApiToken`, `azureServicePrincipal`, `dockerCert`, `vault*`, `conjur*`, `keychain…` | from *other* plugins via the same extension point — enumerate dynamically, don't hardcode. F3 |

**No `mask` binding exists.** Masking of bound variables in build logs is automatic behavior of the plugin; there is no `mask` parameter on any core binding.

**v2 implementation (ML-R4 — L4):**
- `withCredentials(id: String, purpose: BoundPurpose, block: () -> T)` — DSL entry point
- Supported bindings at L4: `string` (→ `API_KEY` purpose), `usernamePassword` (→ `USERNAME_PASSWORD` purpose)
- Deferred to ML-R4.1: `sshUserPrivateKey`, `file`, `certificate`, `zip`, and all plugin-contributed bindings
- Error messages match Jenkins verbatim: missing ID → `"Could not find credentials entry with ID 'xxx'"`; type mismatch → `"Credentials 'xxx' is of type 'SshCredentials' where 'StringCredentials' was expected."`
- See [[ADR-0049-credentials-local]] §D6

### 1.7 junit — v1424.vc64a_edde7777 (199k installs)
Source: `…/steps/junit/`

| step | signature | notes | source |
|---|---|---|---|
| `junit` | `junit(testResults: String, allowEmptyResults: boolean, checksName: String, healthScaleFactor: double = 1.0, keepLongStdio: boolean, keepProperties: boolean, keepTestNames: boolean, skipMarkingBuildUnstable: boolean, skipMarkingStageUnstable: boolean, skipOldReports: boolean, skipPublishingChecks: boolean, stdioRetention: String, testDataPublishers: List)` | `testResults` required. `keepLongStdio` deprecated → `stdioRetention`. F1 (testResults, allowEmptyResults), F3 (rest) |

### 1.8 pipeline-utility-steps — v3.810.va_7672d206740 (50k installs)
Source: `…/steps/pipeline-utility-steps/`

| step | signature | notes | source |
|---|---|---|---|
| `readJSON` | `readJSON(file: String, text: String, returnPojo: boolean = false)` | file XOR text. F1 |
| `writeJSON` | `writeJSON(json: Object, file: String, pretty: int, returnText: boolean = false)` | `json` required; file XOR returnText. F1 |
| `readYaml` | `readYaml(file: String, text: String, maxAliasesForCollections: int, codePointLimit: int)` | file XOR text. F1 |
| `writeYaml` | `writeYaml(data: Object, datas: List, file: String, charset: String = UTF-8, overwrite: boolean = false, returnText: boolean = false)` | data XOR datas; file XOR returnText. F1 |
| `readProperties` | `readProperties(file: String, text: String, defaults: Map, interpolate: boolean, charset: String)` | precedence: defaults < file < text. F2 |
| `readManifest` | `readManifest(file: String, text: String)` | F3 |
| `sha1` / `sha256` | `sha1(file: String)` / `sha256(file: String)` | returns hex digest. F2 |
| `zip` | `zip(zipFile: String, archive: boolean, defaultExcludes: boolean, dir: String, exclude: String, file: String, glob: String, overwrite: boolean)` | `zipFile` required. F2 |
| `unzip` | `unzip(zipFile: String, charset: String, dir: String, file: String, glob: String, quiet: boolean, read: boolean, test: boolean)` | `read:true` returns Map. F2 |
| `tar` / `untar` | `tar(file: String, archive, compress, defaultExcludes, dir, exclude, glob, overwrite)`; `untar(file, dir, glob, keepPermissions, quiet, test)` | F3 |
| `touch` | `touch(file: String, timestamp: long)` | F3 |
| `findFiles` | `findFiles(glob: String, excludes: String)` | F2 |

### 1.9 lockable-resources — v1554.v491fcb_27c716 (74k installs)
Source: `…/steps/lockable-resources/`

| step | signature | notes |
|---|---|---|
| `lock` | `lock(resource: String, label: String, quantity: int, inversePrecedence: boolean, priority: int, reason: String, resourceSelectStrategy: String = "sequential", skipIfLocked: boolean, variable: String, timeoutForAllocateResource: long = 0, timeoutUnit: String = MINUTES, extra: List)` | `resource` XOR `label`; quantity 0/empty ⇒ all matching. timeout 0 ⇒ wait indefinitely. F1 (resource, label), F2 (quantity, variable, reason), F3 (rest) |
| `updateLock` | `updateLock(resource: String, addLabels, setLabels, removeLabels, setNote, createResource: boolean, deleteResource: boolean)` | F3 |

### 1.10 slack + email-ext
Sources: `…/steps/slack/` (v795.v4b_9705b_e6d47, 31k), `…/steps/email-ext/` (v2038.v7b_8817a_499d9, 171k)

| step | signature | notes |
|---|---|---|
| `slackSend` | `slackSend(message: String, channel: String, color: String, token: String, tokenCredentialId: String, teamDomain: String, baseUrl: String, botUser: boolean, failOnError: boolean, iconEmoji: String, username: String, notifyCommitters: boolean, replyBroadcast: boolean, sendAsText: boolean, timestamp: String, attachments: Object, blocks: Object)` | **all params optional**. color: `good|warning|danger|#hex`. F1 (message, channel, tokenCredentialId), F2 (color, botUser, failOnError), F3 (rest) |
| `emailext` | `emailext(subject: String, body: String, to: String, attachLog: boolean, attachmentsPattern: String, compressLog: boolean, from: String, inlineAttachmentsPattern: String, mimeType: String, postsendScript: String, presendScript: String, recipientProviders: List<RecipientProvider>, replyTo: String, saveOutput: boolean)` | `subject`+`body` required. F1 (subject, body, to), F2 (attachmentsPattern, attachLog, recipientProviders), F3 (scripts) |

### 1.11 docker-workflow — v653.v2f2c08eff0ec (79k installs)
The **real-world surface is the `docker` global variable**:
- `docker.image(id).inside(args = '') { … }`
- `docker.image(id).withRun(args = '') { c -> … }`
- `docker.build(imageWithTag, args = '')` → `.push(tag = '')`, `.pull()`, `.run(args, command)`, `.withRegistry(url, credentialsId) { … }`, `.withServer(uri, credentialsId) { … }`, `.image(id)`
- Declarative agents: `agent { docker { image '…', args '…', label '…', registryUrl '…', registryCredentialsId '…' } }`, `agent { dockerfile true }` / `dockerfile { dir '…', filename '…', additionalBuildArgs '…', label '…' }`
F1 (image, inside, build), F2 (withRun, push, agent docker/dockerfile), F3 (registry/server forms).

### 1.12 kubernetes — v4547.v52f3080db_8cd (28k installs)
Declarative `agent { kubernetes { … } }`: `label`, `inheritFrom`, `yaml`, `yamlFile`, `yamlMergeStrategy`, `cloud`, `namespace`, `containers`/`containerTemplate` (deprecated), `defaultContainer`, `envVars`, `volumes` (configMapVolume, emptyDirVolume, hostPathVolume, nfsVolume, persistentVolumeClaim, secretVolume, dynamicPVC…), `workspaceVolume`, `serviceAccount`, `nodeSelector`, `idleMinutes`, `instanceCap`, `showRawYaml`, `podRetention`, `supplementalGroups`, `customWorkspace`, `imagePullSecrets`, `annotations`, `activeDeadlineSeconds`, `slaveConnectTimeout`, `runAsUser`/`runAsGroup`, `schedulerName`, `hostNetwork`, `nodeUsageMode`, `agentContainer`, `agentInjection`. Scripted equivalents: `podTemplate(...)` step (same params) + `container(name: String, shell: String)` + `containerLog` + `kubeconfig`. F1 (label, yaml, inheritFrom, defaultContainer), F2 (containers, volumes, namespace, cloud), F3 (rest).

### 1.13 Console wrappers & orchestration
Sources: `…/steps/timestamper/`, `…/steps/ansicolor/`, `…/steps/pipeline-milestone-step/`, `…/steps/pipeline-build-step/`, `…/steps/copyartifact/`

| step | plugin / version | signature | notes |
|---|---|---|---|
| `timestamps` | timestamper 1.30 (176k) | `timestamps { … }` — **no params** | F1 |
| `ansiColor` | ansicolor 542.v03d235fee02d (39k) | `ansiColor(colorMapName: String)` | `xterm`, `vga`, `gnome-terminal`, `css`. F2 |
| `milestone` | pipeline-milestone-step (190k, bundled) | `milestone(ordinal: int, label: String, unsafe: boolean)` | ordinal de-facto optional. F2 |
| `build` | pipeline-build-step (191k, bundled) | `build(job: String, parameters: List<ParameterValue>, propagate: boolean = true, quietPeriod: int, wait: boolean = true, waitForStart: boolean = false)` | F1 (job, parameters, propagate), F2 (wait), F3 (rest) |
| `copyArtifacts` | copyartifact (35k) | `copyArtifacts(projectName: String, selector: BuildSelector, filter: String, excludes: String, target: String, flatten: boolean, fingerprintArtifacts: boolean, optional: boolean, parameters: String, resultVariableSuffix: String, includeBuildNumberInTargetPath: boolean = false)` | selector: `specific(buildNumber)`, `latestSavedBuild`, `permalink(id)`, `status`, `upstream`, `workspace`, `buildParameter`. F1 (projectName), F2 (selector, filter, target) |

### 1.14 Declarative `options{}` (pipeline-model-definition + core)
Pipeline-level: `buildDiscarder(logRotator(numToKeepStr: '1', …))`, `checkoutToSubdirectory('dir')`, `disableConcurrentBuilds(abortPrevious: boolean)`, `disableResume()`, `newContainerPerStage()`, `overrideIndexTriggers(boolean)`, `preserveStashes(buildCount: int)`, `quietPeriod(int)`, `retry(int)`, `skipDefaultCheckout()`, `skipStagesAfterUnstable()`, `timeout(time: 1, unit: 'HOURS')`, `timestamps()`, `parallelsAlwaysFailFast()`, `disableRestartFromStage()`. Stage-level `options{}` accepts only step-like wrappers (`retry`, `timeout`, `timestamps`) and stage-relevant declarative options.

---

## 2. Top 25 by real-world usage

**Basis:** plugins.jenkins.io install counts (fetched 2026-08-26) + ecosystem prominence. **Caveat:** install count ≠ step usage — bundled plugins are installed everywhere even when rarely used; conversely slack/kubernetes are used constantly where installed.

| # | step | plugin (installs) |
|---|---|---|
| 1 | `sh` | workflow-durable-task-step (194k) |
| 2 | `echo` | workflow-basic-steps (193k) |
| 3 | `stage` | pipeline-stage-step / declarative (193k/189k) |
| 4 | `node` | workflow-durable-task-step (194k) |
| 5 | `checkout` | workflow-scm-step (200k) |
| 6 | `dir` | workflow-durable-task-step (194k) |
| 7 | `junit` | junit (199k) |
| 8 | `withCredentials` | credentials-binding (198k) |
| 9 | `git` | git (197k) |
| 10 | `timeout` | workflow-basic-steps (193k) |
| 11 | `writeFile` / `readFile` | workflow-basic-steps (193k) |
| 12 | `withEnv` | workflow-basic-steps (193k) |
| 13 | `error` | workflow-basic-steps (193k) |
| 14 | `retry` | workflow-basic-steps (193k) |
| 15 | `archiveArtifacts` | workflow-basic-steps (193k) |
| 16 | `script` | pipeline-model-definition (189k) |
| 17 | `catchError` | workflow-basic-steps (193k) |
| 18 | `sleep` | workflow-basic-steps (193k) |
| 19 | `bat` | workflow-durable-task-step (194k) |
| 20 | `pwd` / `fileExists` | workflow-basic-steps (193k) |
| 21 | `timestamps` | timestamper (176k) |
| 22 | `input` | pipeline-input-step (192k) |
| 23 | `properties` | workflow-multibranch (192k) |
| 24 | `emailext` | email-ext (171k) |
| 25 | `docker` global var | docker-workflow (79k) |

Just below the cut: `lock` (74k), `build` (191k), `slackSend` (31k), kubernetes agent (28k), `copyArtifacts` (35k), `milestone`, `ansiColor` (39k), `readJSON`/`writeJSON`/`readYaml` (50k).

---

## 3. `sh` deep-dive

**Complete current signature** (workflow-durable-task-step 1479.v56e587f413a_7, page-verified):

```groovy
sh(String script,                    // REQUIRED
   String encoding      = null,      // optional; null ⇒ system default encoding of the node
   String label         = null,      // optional; display label in step view / Blue Ocean
   boolean returnStatus = false,     // optional; exit code instead of exception
   boolean returnStdout = false)     // optional; stdout as String instead of build log
```

Semantics:
- Multi-line scripts allowed; runs via system default shell with **`-xe`** flags (`set +e` / `set +x` to disable).
- Interpreter selection via shebang first line (`#!/usr/bin/perl` etc.).
- `returnStdout` returns stdout as String (stderr still to log); `.trim()` idiom for trailing newline. `returnStdout` and `returnStatus` mutually exclusive in practice.
- `encoding` applies to the returned string when `returnStdout`, and to log copying otherwise.
- `label` renames the step in Pipeline Steps view/Blue Ocean (version-sensitive param).

**Wrapper-step ecosystem — exact mapping:**

| Wrapper | Signature | What it does around `sh` |
|---|---|---|
| `timeout(time, unit, activity) { sh … }` | workflow-basic-steps | aborts the sh block; `activity`-mode resets on log output |
| `withEnv(List<String> overrides) { sh … }` | workflow-basic-steps | `VAR=value` entries; `PATH+EXTRA=/dir` **prepends** to PATH |
| `environment { KEY = value }` (Declarative directive) | pipeline-model-definition | pipeline/stage-scoped env vars; `credentials('id')` helper binds secret text/file/userpass (`MYVAR`, `MYVAR_USR`, `MYVAR_PSW`) |
| `retry(count) { sh … }` | workflow-basic-steps | re-runs body on failure (aborts not retried) |
| `catchError(buildResult, stageResult, message) { sh … }` | workflow-basic-steps | swallows failure, sets results |
| `dir(path) { sh … }` / `ws(dir) { sh … }` | workflow-durable-task-step | cwd / whole workspace scoping |
| `node(label) { sh … }` | workflow-durable-task-step | allocates the executor/workspace the sh runs in — **sh requires a node context** (agent) |
| `timestamps { sh … }` | timestamper | prefixes console lines |
| `ansiColor(colorMapName) { sh … }` | ansicolor | ANSI color passthrough |
| `lock(resource|label) { sh … }` | lockable-resources | serializes critical sections |
| `withCredentials([…]) { sh … }` | credentials-binding | bound vars visible to sh; auto-masked in log |
| `container(name) { sh … }` | kubernetes | runs sh inside a pod container |
| `script { sh … }` | pipeline-model-definition | escape hatch *inside* declarative `steps{}` |

Canonical composition:
```groovy
node('linux') {
  checkout(scm)
  dir('build') {
    withEnv(['PATH+MAVEN=/opt/maven/bin']) {
      timeout(time: 15, unit: 'MINUTES') {
        retry(2) {
          def v = sh(script: 'make version', returnStdout: true).trim()
        }
      }
    }
  }
}
```

---

## 4. Competing surfaces (explicit notes)

| Surface A (step / scripted) | Surface B (declarative) | Notes |
|---|---|---|
| `timeout(time:, unit:) { }` step | `options { timeout(time: 1, unit: 'HOURS') }` | same underlying TimeoutStep; options form is pipeline/stage-wide |
| `retry(count) { }` step | `options { retry(3) }` | same step; options form retries entire pipeline/stage |
| `timestamps { }` step | `options { timestamps() }` | same wrapper, two spellings |
| `input message: …` step | `stage('X') { input { … } }` directive | directive form supports message/id/ok/submitter/parameters/when |
| `withEnv([…])` step | `environment { }` directive (+ `credentials()` helper) | declarative is static; withEnv is dynamic/list-based |
| `node(label) { }` step | `agent { … any/label/docker/dockerfile/kubernetes }` | agent also does checkout + ws implicitly |
| `properties([...])` | `options { disableConcurrentBuilds() … }` | both mutate job properties |
| scripted `stage('name') { }` | declarative `stage('name') { steps… }` | different plugins, different grammars, same word |
| `checkout(git(…))` / `git …` step | implicit agent checkout | implicit vs explicit SCM |
| `lock { }` step | (no declarative option exists) | must be used as a step even in declarative |
| `parallel(map)` keyword | `stages { parallel { stage… } }` + `failFast` | keyword vs directive forms |

---

## 5. Source list (all fetched 2026-08-26)

Steps reference pages under `https://www.jenkins.io/doc/pipeline/steps/`: workflow-basic-steps, core, workflow-durable-task-step, workflow-cps, pipeline-stage-step, workflow-multibranch, pipeline-model-definition, pipeline-input-step, git, workflow-scm-step, credentials-binding, junit, pipeline-utility-steps, lockable-resources, slack, email-ext, docker-workflow, kubernetes, timestamper, ansicolor, pipeline-milestone-step, pipeline-build-step, copyartifact.

Docs & syntax: `https://www.jenkins.io/doc/book/pipeline/syntax/`; docker-workflow plugin docs; ansicolor README.

Ownership verification: `jenkinsci/*` GitHub trees via API listing `*Step.java`.

Versions & install counts: `https://plugins.jenkins.io/api/plugin/<id>` + install-stats badges.

Version-sensitive items flagged: `sh.label`, `input.cancel`, `disableConcurrentBuilds(abortPrevious)`, junit `keepLongStdio`→`stdioRetention` deprecation.
