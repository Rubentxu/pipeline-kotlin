# Specification — PipelineK Affordance API

**Status:** Proposed  
**Purpose:** make PipelineK self-describing for humans and agents without creating a second semantic registry.

## 1. Concept

The API is HATEOAS-inspired but local/CLI-native. A client starts at one root representation and follows typed links/actions to discover resources and valid operations.

No HTTP server is required.

## 2. Root command

```text
pipelinek api --json
```

Minimum response:

```json
{
  "schema": "pipeline.dev/cli-affordance/v1",
  "formatVersion": "1.0",
  "runtimeVersion": "0.x.y",
  "catalogDigest": "sha256:...",
  "links": [
    {"rel": "steps", "command": ["pipelinek", "api", "steps", "--json"]},
    {"rel": "plugins", "command": ["pipelinek", "api", "plugins", "--json"]},
    {"rel": "libraries", "command": ["pipelinek", "api", "libraries", "--json"]},
    {"rel": "profiles", "command": ["pipelinek", "api", "profiles", "--json"]}
  ]
}
```

Commands SHALL be argv arrays, never shell snippets.

## 3. Format versioning

- major version change = incompatible representation;
- minor/additive fields = compatible;
- consumers MUST ignore unknown fields within the supported major version;
- `schema` and `formatVersion` are mandatory.

## 4. Resource collections

Initial resource kinds:

- runtime;
- steps;
- step;
- plugins;
- plugin;
- libraries;
- library;
- capabilities;
- profiles;
- run/read links where the required state exists.

## 5. Step representation

`pipelinek api step <step-id> --json` SHALL project, where available:

- Step ID/name;
- input/output schema;
- effects;
- replay policy;
- recovery policy;
- required capabilities;
- execution location;
- provider plugin identity;
- plugin release version/digest;
- publisher/families/delivery/trust evidence;
- LSP/documentation metadata;
- allowed actions;
- blocked actions with typed reasons.

## 6. State-dependent affordances

The representation SHOULD only advertise currently meaningful actions.

Example blocked invocation:

```json
{
  "actions": [],
  "blockedActions": [
    {
      "rel": "invoke",
      "reason": {
        "code": "MISSING_CAPABILITY",
        "missing": ["network.egress"]
      }
    }
  ]
}
```

No tool-specific logic decides this. It is a projection of real admission.

## 7. Catalog digest

The root SHALL expose a stable `catalogDigest` computed from canonical, sorted identities including:

- PipelineK runtime/tooling schema version;
- installed plugin release digests;
- resolved library catalogue identities where applicable;
- profile schema/version set;
- introspection schema version.

The digest is for discovery-cache invalidation, not a security signature.

## 8. Human commands

Human-friendly commands are projections over the same service:

```text
pipelinek steps list
pipelinek steps describe <id>
pipelinek explain step <id>
pipelinek explain plugin <id>
pipelinek explain library <id>
```

They SHALL NOT own a second metadata table.

## 9. Agent mode

Structured agent output may be provided through:

```text
--output agent
--json
```

The output must already be redacted and include stable error/action codes.

## 10. MCP/LSP reuse

MCP and LSP consume the application-level introspection contract directly. They do not shell out to parse CLI text and do not maintain manually duplicated Step catalogues.

## 11. Error contract

Every machine-readable rejection contains:

```json
{
  "code": "STABLE_CODE",
  "message": "human readable explanation",
  "details": {},
  "actions": []
}
```

Do not encode semantics only in natural-language messages.

## 12. Exit criteria

- newly installed external plugin appears in `api steps` without CLI code modification;
- removing a plugin changes `catalogDigest`;
- CLI human rendering and JSON rendering derive from one model;
- blocked actions match runtime admission decisions;
- JSON format passes compatibility fixtures across minor evolution.

## 13. Compiler facts are projections — GR-016

When composition exposes compiler information, project the real supported profile/format identity, enabled cache modes and applicable diagnostic/hit reasons through the same read model. Do not advertise persistence/session reuse on a memory-only build or return simulated profile defaults. Fields/actions appear only when actually implemented and syntax-validated.

Catalogue identity sorts unordered resource membership; compilation identity preserves effective dependency precedence. `catalogDigest` is not interchangeable with `CacheKey.v2` and neither is a signature or runtime admission verdict. Per-invocation capability/trust changes are evaluated against current context, not replayed from a discovery or compiler cache.
