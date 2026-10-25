package io.github.silbaram.prism.admin

import com.sun.net.httpserver.HttpServer
import io.github.silbaram.prism.admin.service.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.time.Duration
import java.time.Instant

class PipelineMonitorTest {
    @Test fun `unconfigured monitor does not report a healthy pipeline`() {
        val result = PipelineMonitor("", "").observe()
        assertEquals(PipelineObservationState.NOT_CONFIGURED, result.state)
        assertNull(result.snapshot)
    }

    @Test fun `monitor reads real HTTP responses and hides stale data after errors`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        var status = 200
        var body = """{"observedAt":"2026-10-23T00:00:00Z","mode":"KAFKA","consumerEnabled":false,"inbox":{"pendingCount":2,"dueCount":1,"attemptedPendingCount":1,"completedCount":5,"rejectedCount":1,"oldestPendingAgeSeconds":20},"outbox":{"pendingCount":1,"warehousePendingCount":0,"deadLetterPendingCount":1,"oldestKnownPendingAgeSeconds":null,"unknownAgeCount":1}}"""
        server.createContext("/api/internal/pipeline/diagnostics") { exchange ->
            assertEquals("test-key", exchange.requestHeaders.getFirst("X-Prism-Api-Key"))
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            val monitor = PipelineMonitor("http://127.0.0.1:${server.address.port}", "test-key")
            val healthy = monitor.observe()
            assertEquals(PipelineObservationState.AVAILABLE, healthy.state)
            assertEquals(2L, healthy.snapshot!!.inbox.pendingCount)
            assertEquals("KAFKA", healthy.snapshot!!.mode)
            status = 401
            val failed = monitor.observe()
            assertEquals(PipelineObservationState.UNAVAILABLE, failed.state)
            assertNull(failed.snapshot)
            assertEquals(healthy.lastSuccessAt, failed.lastSuccessAt)
            status = 200
            body = """{"mode":"DIRECT"}"""
            assertEquals(PipelineObservationState.UNAVAILABLE, monitor.observe().state)
            body = "x".repeat(65_537)
            assertEquals(PipelineObservationState.UNAVAILABLE, monitor.observe().state)
        } finally { server.stop(0) }
    }

    @Test fun `a stalled response body cannot hang the admin page indefinitely`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = java.util.concurrent.Executors.newCachedThreadPool()
        server.executor = executor
        server.createContext("/api/internal/pipeline/diagnostics") { exchange ->
            exchange.sendResponseHeaders(200, 100)
            try { Thread.sleep(10_000) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
            exchange.close()
        }
        server.start()
        try {
            val start = Instant.now()
            val result = PipelineMonitor("http://127.0.0.1:${server.address.port}", "test-key").observe()
            assertEquals(PipelineObservationState.UNAVAILABLE, result.state)
            assertTrue(Duration.between(start, Instant.now()).seconds < 6)
        } finally { server.stop(0); executor.shutdownNow() }
    }
}
