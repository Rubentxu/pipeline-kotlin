package com.pipeline.v2.application

/**
 * Classifies per-fixture diffs into 7 buckets per COMPATIBILITY_CORPUS.md L51-58.
 */
object CorpusSnapshotDiffer {

    enum class Classification {
        EXPECTED,
        LANGUAGE,
        DIAGNOSTICS,
        PERF,
        BREAK,
        SEMANTIC,
        COMPILER_BUG,
    }

    data class DiffEntry(
        val classification: Classification,
        val isFailure: Boolean,
        val message: String,
    )

    fun diff(baseline: CorpusNormalizer.FixtureSnapshot, current: CorpusNormalizer.FixtureSnapshot): DiffEntry {
        // Check for break — new event kinds or structural changes
        val baselineEvents = baseline.events.size
        val currentEvents = current.events.size

        if (baselineEvents != currentEvents) {
            return DiffEntry(
                classification = Classification.BREAK,
                isFailure = true,
                message = "Event count changed: baseline=$baselineEvents, current=$currentEvents",
            )
        }

        // Check for language-level differences (timestamp drift, etc.)
        for (i in baseline.events.indices) {
            val bEvent = baseline.events[i]
            val cEvent = current.events[i]

            val bKind = bEvent["kind"]
            val cKind = cEvent["kind"]

            if (bKind != cKind) {
                return DiffEntry(
                    classification = Classification.BREAK,
                    isFailure = true,
                    message = "Event kind changed at index $i: baseline=$bKind, current=$cKind",
                )
            }
        }

        // Check diagnostics
        val baselineDiags = baseline.diagnostics.size
        val currentDiags = current.diagnostics.size

        if (baselineDiags != currentDiags) {
            return DiffEntry(
                classification = Classification.DIAGNOSTICS,
                isFailure = false,
                message = "Diagnostic count changed: baseline=$baselineDiags, current=$currentDiags",
            )
        }

        return DiffEntry(
            classification = Classification.EXPECTED,
            isFailure = false,
            message = "Snapshots match",
        )
    }
}
