# Appendix — `Rubentxu/asdf-pipelinek` follow-up

Although the primary requested split is between product and harness, asdf is a third independently versioned adapter and should not be silently implemented inside either repository.

## Responsibilities

`asdf-pipelinek` owns only the asdf adapter:

- `bin/list-all`;
- `bin/latest-stable`;
- `bin/download`;
- `bin/install`;
- `bin/list-bin-paths`;
- plugin-local tests.

It consumes published PipelineK releases; it never builds PipelineK.

## Required changes

### Strict archive root

Remove wildcard fallback:

```text
pipelinek-${VERSION}*
```

Require exact:

```text
pipelinek-${VERSION}
```

### Exact runtime identity

After installation:

```text
pipelinek version == requested VERSION
```

Mismatch => remove partial install + fail.

### latest-stable

Implement the asdf stable selection callback so prereleases/candidate metadata never become default.

### SemVer ordering

Version discovery must use SemVer ordering, not lexical `sort -u`.

### Modern asdf contract

Test against current asdf CLI and avoid removed `asdf global` semantics.

## Harness responsibility

The harness certifies this repo externally:

- clean install;
- current/which/exec;
- upgrade;
- rollback;
- uninstall;
- stale pin;
- coexistence with mise/manual installations.
