# Deferred backlog — explicitly outside the consolidation critical path

The following ideas are intentionally retained as future opportunities but MUST NOT reopen before LFC-10 unless a current milestone proves they are required:

- controller/worker protocol and remote agents;
- Jenkins controller/plugin integration;
- SaaS/multi-tenant control plane;
- remote scheduling/leases/heartbeats;
- remote output streaming protocol;
- dynamic plugin download during execution;
- graph database as a mandatory runtime dependency;
- native-image primary distribution;
- advanced Kubernetes/container agents beyond a plugin/isolation adapter;
- central secrets backends such as Vault as required infrastructure.

## Re-entry rule

To reactivate any item:

1. state the local-first limitation that requires it;
2. provide measured evidence;
3. run a spike;
4. accept a new ADR;
5. prove it does not break local contracts.
