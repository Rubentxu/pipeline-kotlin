package example.sdkext.consumer

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.output.OutputCursor

/**
 * ENTREGA A: the compile that proves the BOM resolves.
 *
 * This file exists ONLY to name one public type from each of the four published contracts. It
 * implements no behaviour and is asserted on by nothing at runtime; it is compiled by `compileKotlin`
 * as part of this build's normal lifecycle.
 *
 * The proof is the DECLARATION, not this code. `build.gradle.kts` declares
 *
 * ```kotlin
 * implementation(platform("dev.rubentxu.pipeline.v2:pipeline-sdk-bom:$sdkVersion"))
 * implementation("dev.rubentxu.pipeline.v2:pipeline-domain")
 * implementation("dev.rubentxu.pipeline.v2:pipeline-scripting-api")
 * implementation("dev.rubentxu.pipeline.v2:pipeline-events")
 * implementation("dev.rubentxu.pipeline.v2:pipeline-output")
 * ```
 *
 * with NO version on the four. If `pipeline-sdk-bom` did not carry an `api` constraint for each of
 * them, Gradle would fail to resolve four versionless coordinates and this module would not compile.
 * So the four imports below resolve only if the platform applied its dependencyManagement, which is
 * exactly the property being certified. A comment claiming the BOM works would be worth nothing;
 * this compile is the test.
 *
 * `StageScope` is the scripting surface, `DomainEvent` the event contract, `OutputCursor` the output
 * read contract and `FailureKind` the domain contract. One name per contract keeps the proof honest:
 * a build that accidentally resolved only three of the four would fail on the missing import.
 */
object SdkSurfaceConsumer {

    /** Names one type from each contract so all four coordinates must be on the compile classpath. */
    fun namesOneTypeFromEachContract(
        scope: StageScope,
        event: DomainEvent,
        cursor: OutputCursor,
        kind: FailureKind,
    ): List<Any> = listOf(scope, event, cursor, kind)
}
