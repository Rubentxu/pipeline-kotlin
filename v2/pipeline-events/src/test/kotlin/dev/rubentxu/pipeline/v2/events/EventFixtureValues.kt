package dev.rubentxu.pipeline.v2.events

import dev.rubentxu.pipeline.v2.domain.BoundPurpose
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.CredentialsRef
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.scripting.CacheKey
import dev.rubentxu.pipeline.v2.scripting.ScriptDiagnosticSeverity
import dev.rubentxu.pipeline.v2.scripting.ScriptingDiagnostic
import java.nio.file.Path
import java.time.Instant

/**
 * Valores compartidos por el fixture de vocabulario de [DomainEvent].
 *
 * Cada propiedad propia de un evento lleva un valor DISTINTO derivado de su
 * numero. No es decoracion: una `copy` que desplazara campos entre si, o que
 * perdiera uno, solo puede notarse si los valores de partida no se repiten dentro
 * del mismo tipo. Con todos los campos iguales al mismo literal, una copia que
 * intercambiara dos parametros pasaria el test sin decir nada.
 *
 * Todos los valores son deterministas y sin reloj: el fixture se construye dos
 * veces en el mismo test para comprobar la semantica de valor, y una
 * construccion no reproducible haria esa comparacion vacia.
 */
internal const val RUN_ID = "01987654-3210-fedc-ba98-76543210fedc"

internal val AT: Instant = Instant.parse("2026-01-01T00:00:00Z")

internal fun id(n: Int): String = "evt-$n"

internal fun v(n: Int, k: Int): String = "e${n}f$k"

internal fun i(n: Int, k: Int): Int = n * 100 + k

internal fun l(n: Int, k: Int): Long = n * 10_000L + k

internal fun sha(n: Int, k: Int): String = "d".repeat(60) + "$n$k".padStart(4, '0')

internal fun ruta(n: Int, k: Int): Path = Path.of("/ws", v(n, k))

internal fun cred(n: Int, k: Int): CredentialsId = CredentialsId(v(n, k))

internal fun ref(n: Int, k: Int): CredentialsRef = CredentialsRef(CredentialsId(v(n, k)))

internal fun cacheKey(): CacheKey = CacheKey(CacheKey.sha256Hex("texto", "cp", "2.0.0", "0.39.0"), CacheKey.V1)

internal fun diagnosticos(n: Int): List<ScriptingDiagnostic> = listOf(
    ScriptingDiagnostic(ScriptDiagnosticSeverity.ERROR, v(n, 9), i(n, 1), i(n, 2), v(n, 3)),
)

internal fun artefacto(n: Int, k: Int): ArtifactEntry =
    ArtifactEntry(RUN_ID, v(n, k), v(n, k + 1), sha(n, k + 2), l(n, k + 3), AT)

internal fun entradaStash(n: Int, k: Int): StashedEntry = StashedEntry(v(n, k), sha(n, k + 1), l(n, k + 2))

internal fun entradaRestaurada(n: Int, k: Int): RestoredEntry = RestoredEntry(v(n, k), sha(n, k + 1), l(n, k + 2))

internal fun entradaHtml(n: Int, k: Int): HtmlReportEntry = HtmlReportEntry(v(n, k), sha(n, k + 1), l(n, k + 2))

// ----------------------------------------------------------------------
// Fixture: una instancia de cada subtipo concreto de DomainEvent
// ----------------------------------------------------------------------

/**
 * Una instancia de cada subtipo concreto de [DomainEvent], en el orden en que
 * estan declarados en `DomainEvent.kt`.
 *
 * Vive fuera de la clase de test para que el test del vocabulario (leyes de valor,
 * unicidad de `kind`, `copy`) y el del proyector (tabla de sujetos) compartan
 * exactamente la misma lista. Dos listas paralelas serian dos cosas que pueden
 * dejar de coincidir, y la que dejara de coincidir seria la que nadie mira.
 */
internal fun vocabulario(): List<DomainEvent> =
    cicloDeEjecucion() + credencialesYScm() + ficherosYStash() + controlDeFlujo() + cerrojoEntradaYHttp()

@Suppress("DEPRECATION")
internal fun cicloDeEjecucion(): List<DomainEvent> = listOf(
    RunStarted(id(1), RUN_ID, l(1, 0), AT, v(1, 1)),
    CompilationStarted(id(2), RUN_ID, l(2, 0), AT),
    CompilationFinished(id(3), RUN_ID, l(3, 0), AT, cacheKey(), diagnosticos(3)),
    RunFinished(id(4), RUN_ID, l(4, 0), AT, v(4, 1), diagnosticos(4)),
    StageStarted(id(5), RUN_ID, l(5, 0), AT, i(5, 1), v(5, 2)),
    StageFinished(id(6), RUN_ID, l(6, 0), AT, i(6, 1), v(6, 2), v(6, 3)),
    StageSkipped(id(7), RUN_ID, l(7, 0), AT, i(7, 1), v(7, 2), v(7, 3)),
    StepStarted(id(8), RUN_ID, l(8, 0), AT, i(8, 1), i(8, 2), v(8, 3), v(8, 4)),
    StepFinished(id(9), RUN_ID, l(9, 0), AT, i(9, 1), i(9, 2), v(9, 3), v(9, 4)),
    StepFailed(id(10), RUN_ID, l(10, 0), AT, i(10, 1), v(10, 2), v(10, 3), FailureKind.SCRIPT, v(10, 4)),
    EchoOutputCaptured(id(11), RUN_ID, l(11, 0), AT, i(11, 1), v(11, 2)),
    AgentResolved(id(12), RUN_ID, l(12, 0), AT, v(12, 1), null),
    ExecutionTargetResolved(id(13), RUN_ID, l(13, 0), AT, i(13, 1), v(13, 2), v(13, 3), v(13, 4), v(13, 5)),
    ParallelBranchStarted(id(14), RUN_ID, l(14, 0), AT, i(14, 1), v(14, 2), i(14, 3)),
    ParallelBranchFinished(id(15), RUN_ID, l(15, 0), AT, i(15, 1), v(15, 2), i(15, 3), v(15, 4)),
    RetryAttemptStarted(id(16), RUN_ID, l(16, 0), AT, i(16, 1), i(16, 2), v(16, 3), v(16, 4), i(16, 5), i(16, 6)),
    RetryAttemptFinished(
        id(17), RUN_ID, l(17, 0), AT, i(17, 1), i(17, 2), v(17, 3), v(17, 4), i(17, 5), i(17, 6), v(17, 7),
    ),
    TimeoutScheduled(id(18), RUN_ID, l(18, 0), AT, l(18, 1), v(18, 2), v(18, 3), v(18, 4), i(18, 5), i(18, 6)),
    StepAdmissionObserved(id(19), RUN_ID, l(19, 0), AT, i(19, 1), i(19, 2), v(19, 3), v(19, 4), i(19, 5)),
)

internal fun credencialesYScm(): List<DomainEvent> = listOf(
    CredentialBound(id(20), RUN_ID, l(20, 0), AT, cred(20, 1), BoundPurpose.API_KEY),
    CredentialUsed(id(21), RUN_ID, l(21, 0), AT, cred(21, 1), BoundPurpose.SSH_KEY, i(21, 2)),
    CredentialUnbound(id(22), RUN_ID, l(22, 0), AT, cred(22, 1)),
    GitCheckoutStarted(id(23), RUN_ID, l(23, 0), AT, v(23, 1), v(23, 2), ref(23, 3)),
    GitCheckoutCompleted(id(24), RUN_ID, l(24, 0), AT, v(24, 1), v(24, 2), v(24, 3), v(24, 4), l(24, 5)),
    GitCheckoutFailed(id(25), RUN_ID, l(25, 0), AT, v(25, 1), v(25, 2), v(25, 3), i(25, 4)),
    GitPollChanged(id(26), RUN_ID, l(26, 0), AT, v(26, 1), v(26, 2), v(26, 3), v(26, 4)),
)

internal fun ficherosYStash(): List<DomainEvent> = listOf(
    FileWritten(id(27), RUN_ID, l(27, 0), AT, ruta(27, 1), sha(27, 2), l(27, 3), true),
    FileRead(id(28), RUN_ID, l(28, 0), AT, ruta(28, 1), sha(28, 2), l(28, 3)),
    FileExistsChecked(id(29), RUN_ID, l(29, 0), AT, ruta(29, 1), true),
    ArtifactArchived(id(30), RUN_ID, l(30, 0), AT, listOf(artefacto(30, 1))),
    ArtifactArchiveFailed(id(31), RUN_ID, l(31, 0), AT, v(31, 1)),
    StashCreated(id(32), RUN_ID, l(32, 0), AT, v(32, 1), v(32, 2), listOf(entradaStash(32, 3))),
    StashRestored(id(33), RUN_ID, l(33, 0), AT, v(33, 1), v(33, 2), listOf(entradaRestaurada(33, 3))),
    StashFailed(id(34), RUN_ID, l(34, 0), AT, v(34, 1), v(34, 2), v(34, 3), v(34, 4)),
    HtmlReportPublished(
        id(35), RUN_ID, l(35, 0), AT, v(35, 1), v(35, 2), v(35, 3), listOf(entradaHtml(35, 4)), v(35, 5),
    ),
    HtmlReportSkipped(id(36), RUN_ID, l(36, 0), AT, v(36, 1), v(36, 2), v(36, 3), v(36, 4)),
    HtmlReportFailed(id(37), RUN_ID, l(37, 0), AT, v(37, 1), v(37, 2), v(37, 3), FailureKind.USER, v(37, 4)),
)

internal fun controlDeFlujo(): List<DomainEvent> = listOf(
    DirEntered(id(38), RUN_ID, l(38, 0), AT, v(38, 1), v(38, 2)),
    DirExited(id(39), RUN_ID, l(39, 0), AT, v(39, 1), v(39, 2)),
    DirDeleted(id(40), RUN_ID, l(40, 0), AT, v(40, 1), i(40, 2), sha(40, 3)),
    WsCleaned(id(41), RUN_ID, l(41, 0), AT, i(41, 1), i(41, 2), listOf(v(41, 3)), sha(41, 4)),
    CatchErrorTriggered(id(42), RUN_ID, l(42, 0), AT, v(42, 1), v(42, 2), v(42, 3), v(42, 4)),
    StageMarkedUnstable(id(43), RUN_ID, l(43, 0), AT, v(43, 1), v(43, 2)),
    WorkflowLoaded(id(44), RUN_ID, l(44, 0), AT, v(44, 1), i(44, 2), sha(44, 3)),
    WaitUntilPolled(id(45), RUN_ID, l(45, 0), AT, i(45, 1), l(45, 2), true),
    WaitUntilCompleted(id(46), RUN_ID, l(46, 0), AT, i(46, 1), l(46, 2), v(46, 3)),
    PwdResolved(id(47), RUN_ID, l(47, 0), AT, v(47, 1), v(47, 2), sha(47, 3)),
    UnixDetected(id(48), RUN_ID, l(48, 0), AT, true, v(48, 1), sha(48, 2)),
    MilestoneReached(id(49), RUN_ID, l(49, 0), AT, i(49, 1), v(49, 2)),
    MilestoneAborted(id(50), RUN_ID, l(50, 0), AT, i(50, 1), v(50, 2)),
    TimeoutTriggered(id(51), RUN_ID, l(51, 0), AT, v(51, 1), v(51, 2), l(51, 3)),
    TimestampsEntered(id(52), RUN_ID, l(52, 0), AT),
    TimestampsExited(id(53), RUN_ID, l(53, 0), AT),
    DirectiveAdmitted(id(54), RUN_ID, l(54, 0), AT, i(54, 1), v(54, 2), v(54, 3), v(54, 4), v(54, 5)),
    DirectiveDenied(id(55), RUN_ID, l(55, 0), AT, i(55, 1), v(55, 2), v(55, 3), v(55, 4)),
    GateEvaluated(id(56), RUN_ID, l(56, 0), AT, i(56, 1), v(56, 2), listOf(v(56, 3)), true, v(56, 4)),
    PostConditionSelected(
        id(57), RUN_ID, l(57, 0), AT, i(57, 1), v(57, 2), v(57, 3), listOf(v(57, 4)), listOf(v(57, 5)),
    ),
)

internal fun cerrojoEntradaYHttp(): List<DomainEvent> = listOf(
    LockRequested(id(58), RUN_ID, l(58, 0), AT, v(58, 1), v(58, 2), true),
    LockAcquired(id(59), RUN_ID, l(59, 0), AT, v(59, 1)),
    LockReleased(id(60), RUN_ID, l(60, 0), AT, v(60, 1)),
    LockSkipped(id(61), RUN_ID, l(61, 0), AT, v(61, 1), v(61, 2)),
    LockAcquireFailed(id(62), RUN_ID, l(62, 0), AT, v(62, 1), v(62, 2)),
    InputRequested(id(63), RUN_ID, l(63, 0), AT, v(63, 1), v(63, 2), v(63, 3)),
    InputProceed(id(64), RUN_ID, l(64, 0), AT, v(64, 1), v(64, 2)),
    InputAborted(id(65), RUN_ID, l(65, 0), AT, v(65, 1), v(65, 2)),
    InputDenied(id(66), RUN_ID, l(66, 0), AT, v(66, 1)),
    HttpRequestStarted(id(67), RUN_ID, l(67, 0), AT, v(67, 1), v(67, 2), i(67, 3)),
    HttpResponseReceived(id(68), RUN_ID, l(68, 0), AT, v(68, 1), i(68, 2), l(68, 3)),
    HttpStatusRejected(id(69), RUN_ID, l(69, 0), AT, v(69, 1), i(69, 2), v(69, 3)),
    HttpRequestFailed(id(70), RUN_ID, l(70, 0), AT, v(70, 1), v(70, 2)),
)
