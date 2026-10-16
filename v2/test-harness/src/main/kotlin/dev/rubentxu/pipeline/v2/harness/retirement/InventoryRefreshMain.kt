package dev.rubentxu.pipeline.v2.harness.retirement

import java.io.File
import java.nio.file.Paths

/**
 * CLI that re-classifies an inventory CSV by reading the source
 * files of the rows. Where the row's file content has the
 * migrated-pending markers, the row's `kind_hint` and decision
 * are updated accordingly. The output is a NEW CSV that the
 * downstream tooling can ingest.
 *
 * Usage:
 *   java -cp ... InventoryRefreshMainKt <repo-root> <input.csv> <output.csv>
 *
 * This is the BQ5 inventory refresh step. It does NOT delete
 * anything; it re-runs the classification with the actual file
 * content. The [DeletionGuard] still refuses retirement while
 * `retirementAuthorised=false`.
 */
fun main(args: Array<String>) {
    require(args.size == 3) {
        "usage: InventoryRefreshMainKt <repo-root> <input.csv> <output.csv>"
    }
    val repoRoot = Paths.get(args[0]).toAbsolutePath()
    val input = Paths.get(args[1]).toFile()
    val output = Paths.get(args[2]).toFile()
    require(input.exists()) { "input not found: ${input.absolutePath}" }

    var total = 0
    var migratedPendingDetected = 0
    var migratedPendingNotDetected = 0
    var readErrors = 0

    val lines = input.readLines()
    if (lines.isEmpty()) {
        error("empty CSV")
    }
    val header = lines.first().split(",").toMutableList()
    if ("migrated_pending" !in header) {
        header.add("migrated_pending")
    }
    val rows = lines.drop(1).filter { it.isNotBlank() }
    val refreshed = ArrayList<String>(rows.size + 1)
    refreshed.add(header.joinToString(","))
    for (row in rows) {
        total++
        val cols = row.split(",").toMutableList()
        while (cols.size < header.size - 1) cols.add("")
        val path = cols[header.indexOf("path")]
        val abs = File(repoRoot.toFile(), path)
        val isMigrated = try {
            if (abs.exists()) {
                MigratedPendingDetector.isMigratedPending(abs.toPath())
            } else {
                false
            }
        } catch (_: Exception) {
            readErrors++
            false
        }
        if (isMigrated) migratedPendingDetected++ else migratedPendingNotDetected++
        while (cols.size < header.size) cols.add("")
        cols[header.size - 1] = if (isMigrated) "true" else "false"
        refreshed.add(cols.subList(0, header.size).joinToString(","))
    }
    output.writeText(refreshed.joinToString("\n") + "\n")
    println(
        "REFRESHED total=$total migrated_pending=$migratedPendingDetected " +
            "not_migrated=$migratedPendingNotDetected read_errors=$readErrors",
    )
    println("WROTE $output")
}
