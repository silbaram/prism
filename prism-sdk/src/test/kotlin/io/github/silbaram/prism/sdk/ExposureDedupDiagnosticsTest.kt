package io.github.silbaram.prism.sdk

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class ExposureDedupDiagnosticsTest {
    @Test
    fun `warnings are limited to once a minute and include suppressed rejections`() {
        // nanoTime has an arbitrary origin, including negative values.
        val clock = AtomicLong(-100)
        val warnings = mutableListOf<Pair<Long, Long>>()
        val diagnostics = ExposureDedupDiagnostics(clock::get) { total, recent -> warnings.add(total to recent) }
        repeat(3) { diagnostics.reject() }
        clock.addAndGet(Duration.ofMinutes(1).toNanos() - 1)
        diagnostics.reject()
        assertEquals(listOf(1L to 1L), warnings)
        assertEquals(4L, diagnostics.rejectedCount)
        clock.incrementAndGet()
        diagnostics.reject()
        assertEquals(listOf(1L to 1L, 5L to 4L), warnings)
        clock.addAndGet(Duration.ofMinutes(1).toNanos())
        diagnostics.reject()
        assertEquals(6L to 1L, warnings.last())
    }

    @Test
    fun `concurrent rejections are all counted without flooding warnings`() {
        val warnings = CopyOnWriteArrayList<Pair<Long, Long>>()
        val diagnostics = ExposureDedupDiagnostics({ 0L }) { total, recent -> warnings.add(total to recent) }
        val workers = Executors.newFixedThreadPool(8)
        try {
            val tasks = (1..1000).map { workers.submit { diagnostics.reject() } }
            tasks.forEach { it.get(3, TimeUnit.SECONDS) }
            assertEquals(1000L, diagnostics.rejectedCount)
            assertEquals(listOf(1L to 1L), warnings)
        } finally { workers.shutdownNow() }
    }
}
