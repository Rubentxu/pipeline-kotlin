# pipeline-architecture-tests (M0-R3)

Executable assertions that pin the M0-R3 architecture rules (F-ARCH-001 / 002 / 003 / 004 / 011).
One JUnit 5 test class per rule, plus a `@Nested` violation-fixture sub-test per class proving the
scanner detects its own synthetic mutation.

Run all five:

    ./gradlew -p v2 check

Run a single rule:

    ./gradlew -p v2 :pipeline-architecture-tests:test \
        --tests 'dev.rubentxu.pipeline.v2.architecture.FArch001DomainFrameworkFreeTest'

Override the V2 root for local debugging:

    ./gradlew -p v2 :pipeline-architecture-tests:test -Pfitness.v2.root=/abs/path/to/v2

Adding a rule: drop a new `FArchNNNRuleNameTest.kt` next to its peers, reuse `ScannerSupport`,
add a `@Nested` violation fixture. No build-script changes required.

Follow-up: DUP-001 (toolchain block ×5) — consolidate after the next M0 touch.
