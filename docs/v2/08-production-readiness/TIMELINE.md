# Timeline and Dependencies

## Recommended calendar

```mermaid
gantt
    title PipelineK production-readiness closure
    dateFormat  YYYY-MM-DD
    axisFormat  %d/%m

    section Phase 1 — Truth
    PR-001 Current-state projection       :a1, 2026-09-28, 2d
    PR-002 SESSION_POINTER reduction      :a2, after a1, 1d
    PR-003 UAT state split                :a3, after a1, 2d
    PR-004 PR reconciliation              :a4, 2026-09-28, 2d
    PR-007 Debt reconciliation            :a7, after a1, 1d
    PR-006 Admission check design         :a6, after a1, 2d
    PR-005 Dependency batches             :a5, after a4, 3d

    section Phase 2 — Production Ready
    PR-008 Classify full-suite failures   :b8, after a4, 3d
    PR-009 Close HAR-007                  :b9, after b8, 3d
    PR-010 Freeze candidate               :b10, after b9, 1d
    PR-011 Full gate                      :b11, after b10, 2d
    PR-012 Security/supply-chain          :b12, after b10, 2d
    PR-013 Coverage                       :b13, after b10, 2d
    PR-014 Targeted mutation              :b14, after b10, 2d
    PR-015 Memory SLO                     :b15, after b10, 2d
    PR-016 Final admission                :milestone, b16, after b15, 0d

    section Phase 3 — Strategic
    PR-017 RunLifecycleEngine             :c17, after b16, 3d
    PR-018 BodyExecutionEngine            :c18, after c17, 4d
    PR-019 Invocation + Recovery          :c19, after c18, 5d
    PR-020 Coordinator collapse           :c20, after c19, 4d
```

## Dependency graph

```mermaid
flowchart TD
    A[PR-001 Current-state truth] --> B[PR-002 Session pointer]
    A --> C[PR-003 UAT current view]
    A --> D[PR-006 Admission check]
    A --> E[PR-007 Debt reconciliation]

    F[PR-004 PR reconciliation] --> G[PR-005 Dependency batches]
    F --> H[PR-008 Classify 41 failures]

    H --> I[PR-009 HAR-007]
    I --> J[PR-010 Candidate freeze]

    J --> K[PR-011 Full L5]
    J --> L[PR-012 Security/Supply chain]
    J --> M[PR-013 Coverage]
    J --> N[PR-014 Mutation]
    J --> O[PR-015 Memory SLO]

    D --> P[PR-016 RP-5 admission]
    K --> P
    L --> P
    M --> P
    N --> P
    O --> P

    P --> Q[PR-017 Lifecycle]
    Q --> R[PR-018 Body engine]
    R --> S[PR-019 Invocation/Recovery]
    S --> T[PR-020 Coordinator collapse]
```

## Critical path

`PR-004 → PR-008 → PR-009 → PR-010 → PR-011 → PR-016`

The documentation and admission-control work should execute in parallel with the early part of that path.
