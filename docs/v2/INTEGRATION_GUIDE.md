# Integration Guide

## Recommended repository layout

Copy this pack as:

```text
pipeline-kotlin/
  docs/
    v2/
      DESIGN.md
      README.md
      00-context/
      01-product/
      ...
```

## First commit recommendation

Add documentation only. Do not mix the initial architecture-pack commit with code refactors; this makes later ADR/code diffs traceable.

## Suggested next PRs

1. **docs(v2): architecture and roadmap baseline**
2. **build(v2): establish Kotlin 2.4.10 modules and fitness gates**
3. **feat(scripting): Kotlin24 pipeline scripting adapter spike**
4. **feat(domain): event envelope and run state spine**
5. **feat(dsl): Jenkins-familiar DSL + Step SDK vertical**

## Existing docs

Do not delete existing `docs/ARCHITECTURE.md`, PRDs or migration guides immediately. Add a banner when a V2 document supersedes a section. Remove old docs only after corresponding runtime reaches its milestone gate.

## Repomix snapshots

Regenerate snapshots from the actual branch or move generated snapshots out of normative architecture docs. A generated repository snapshot should carry commit SHA/date and never be treated as canonical design specification.
