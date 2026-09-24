package dev.rubentxu.pipeline.v2.spike.stagescoped

/**
 * Shared in-memory [SuspendRuntimeFacade] for tests.
 *
 * Records every dispatched call (in order) and returns scripted outcomes.
 * `internal` so all test classes in this module can share it without
 * crossing a public boundary.
 *
 * Kept here (not in each test file) so the dispatch order, default values,
 * and recording contract are all defined exactly once.
 */
internal class RecordingFacade(
    private val pwdResult: SuspendOutcome = SuspendOutcome.StringOutcome(value = ""),
    private val readFileResult: SuspendOutcome = SuspendOutcome.StringOutcome(value = ""),
    private val fileExistsResult: SuspendOutcome = SuspendOutcome.BooleanOutcome(value = false),
    private val shResult: SuspendOutcome = SuspendOutcome.StringOutcome(value = ""),
    private val isUnixResult: SuspendOutcome = SuspendOutcome.BooleanOutcome(value = false),
) : SuspendRuntimeFacade {

    val dispatchedNames: MutableList<String> = mutableListOf()
    val callCount: Int get() = dispatchedNames.size

    override fun pwd(tmp: Boolean): SuspendOutcome {
        dispatchedNames += "pwd"
        return pwdResult
    }

    override fun readFile(file: String): SuspendOutcome {
        dispatchedNames += "readFile"
        return readFileResult
    }

    override fun fileExists(file: String): SuspendOutcome {
        dispatchedNames += "fileExists"
        return fileExistsResult
    }

    override fun shReturnStdout(script: String, encoding: String?): SuspendOutcome {
        dispatchedNames += "shReturnStdout"
        return shResult
    }

    override fun isUnix(): SuspendOutcome {
        dispatchedNames += "isUnix"
        return isUnixResult
    }
}
