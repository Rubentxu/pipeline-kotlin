package dev.rubentxu.pipeline.v2.credentials.api

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.security.SecureRandom

/** Disposable WU-RP-022 throughput probe (kept: it pins the perf contract). */
class Rp022ThroughputProbe {
    @Test
    fun `redactor throughput floor`() {
        val registry = SecretPatternRegistry()
        registry.addSecret(SecretHandle.plain("supersecretvalue01"))
        val redactor = StreamingRedactor(registry)

        val rnd = SecureRandom()
        val chunk = ByteArray(8192).also { rnd.nextBytes(it) }
        val big = ByteArray(50 * 1024 * 1024)
        var pos = 0
        while (pos < big.size) {
            val n = minOf(chunk.size, big.size - pos)
            System.arraycopy(chunk, 0, big, pos, n); pos += n
        }
        "supersecretvalue01".toByteArray().copyInto(big, 1024)
        "supersecretvalue01".toByteArray().copyInto(big, big.size - 2048)

        repeat(1) { redactor.wrap(ByteArrayInputStream(big)).use { it.readBytes().toString(Charsets.UTF_8) } } // warmup
        val t0 = System.nanoTime()
        val out = redactor.wrap(ByteArrayInputStream(big)).use { it.readBytes().toString(Charsets.UTF_8) }
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("PROBE: 50MiB in ${ms}ms -> ${"%.1f".format(50.0 / (ms / 1000.0))} MB/s")
        check(!out.contains("supersecretvalue01")) { "LEAK" }
        check(out.contains("****")) { "no marker" }
        // Perf floor: must beat the old ~0.1 MB/s by orders of magnitude.
        // Conservative floor 20 MB/s (observed post-rewrite: hundreds of MB/s).
        check(50.0 / (ms / 1000.0) >= 20.0) { "throughput below floor: $ms ms for 50MiB" }
    }
}
