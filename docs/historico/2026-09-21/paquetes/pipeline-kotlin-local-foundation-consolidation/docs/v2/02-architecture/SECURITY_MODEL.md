# Local-first security model

## Trust boundaries

- pipeline source is project code and may execute arbitrary local commands when approved/run;
- plugins are executable code and require verified source/digest/version;
- credentials are higher-sensitivity material with shorter lifetime than normal config;
- terminal/output/event stores are untrusted destinations for raw secrets.

## Controls for 1.0

- locked plugin artifacts with checksums;
- fail-closed credential binding;
- secret redaction before persistence;
- restrictive secret temp-file permissions;
- explicit process environment composition;
- no implicit plugin download during execution;
- optional hardened local sandbox may add OS containment, but standard local execution is never marketed as hostile-code isolation.

## Non-goal

1.0 does not claim to safely execute untrusted third-party pipeline code on a developer workstation without OS-level isolation.
