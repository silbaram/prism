package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.config.*
import io.github.silbaram.prism.common.rest.dto.event.*
import okhttp3.mockwebserver.*
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.*
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class SdkDiagnosticsTest {
    private class TestClock : Clock() {
        val nanos = AtomicLong()
        val wall = AtomicReference(Instant.parse("2026-10-19T00:00:00Z"))
        override fun instant(): Instant = wall.get()
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = Clock.fixed(instant(), zone)
        fun advance(duration: Duration) { nanos.addAndGet(duration.toNanos()); wall.updateAndGet { it.plus(duration) } }
    }
    private enum class ConfigReply { NORMAL, HTTP_FAILURE, MALFORMED, UNCONDITIONAL_304, OUTDATED }
    private enum class EventReply { NORMAL, HTTP_FAILURE, INCOMPLETE, NO_RESPONSE }
    private val mapper = jacksonObjectMapper()
    private val server = MockWebServer()
    private val time = TestClock()
    private val configReply = AtomicReference(ConfigReply.NORMAL)
    private val eventReply = AtomicReference(EventReply.NORMAL)
    private val eventStatus = AtomicReference<(ClientEvent) -> EventStatus>({ EventStatus.ACCEPTED })
    private val config = AtomicReference(configuration())
    private val batches = CopyOnWriteArrayList<List<ClientEvent>>()
    private val configEntered = CountDownLatch(1)
    private val eventsEntered = CountDownLatch(1)
    private val streamEntered = CountDownLatch(1)
    private val streamPermit = CountDownLatch(1)
    private val streamCount = AtomicInteger()
    @Volatile private var configGate: CountDownLatch? = null
    @Volatile private var eventGate: CountDownLatch? = null
    private var local: LocalEvaluationClient? = null

    private fun configuration(version: String = "a".repeat(64), revision: Long = 0) = ConfigResponse(version,
        listOf(ExperimentConfig("checkout", "ACTIVE", listOf(VariantConfig("A", 100)))),
        HoldoutConfig(configured = true), revision)

    @BeforeEach fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v1/config" -> {
                    val current = config.get()
                    configGate?.let { configEntered.countDown(); it.await(10, TimeUnit.SECONDS) }
                    val tag = "\"${current.version}\""
                    when (configReply.get()) {
                        ConfigReply.HTTP_FAILURE -> MockResponse().setResponseCode(503)
                        ConfigReply.MALFORMED -> MockResponse().setBody("{secret-invalid-json")
                        ConfigReply.UNCONDITIONAL_304 -> MockResponse().setResponseCode(304)
                        ConfigReply.OUTDATED -> MockResponse().setBody(mapper.writeValueAsString(configuration()))
                        else -> if (request.getHeader("If-None-Match") == tag) MockResponse().setResponseCode(304)
                            else MockResponse().setHeader("ETag", tag).setBody(mapper.writeValueAsString(current))
                    }
                }
                "/v1/events" -> {
                    val batch = mapper.readValue<EventsRequest>(request.body.readUtf8()).events
                    batches.add(batch)
                    eventGate?.let { eventsEntered.countDown(); it.await(5, TimeUnit.SECONDS) }
                    when (eventReply.get()) {
                        EventReply.HTTP_FAILURE -> MockResponse().setResponseCode(500)
                        EventReply.INCOMPLETE -> MockResponse().setBody("""{"results":[]}""")
                        EventReply.NO_RESPONSE -> MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        else -> MockResponse().setBody(mapper.writeValueAsString(EventsResponse(batch.map {
                            EventResult(it.eventId, eventStatus.get().invoke(it))
                        })))
                    }
                }
                "/v1/config/stream" -> {
                    streamEntered.countDown()
                    streamPermit.await(10, TimeUnit.SECONDS)
                    if (streamCount.incrementAndGet() != 1) MockResponse().setResponseCode(503)
                    else MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("event: config\ndata: ${mapper.writeValueAsString(configuration("b".repeat(64), 1))}\n\n: heartbeat\n\n")
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
    }

    private fun options(maxAge: Duration? = Duration.ofMinutes(5), policy: StaleConfigPolicy = StaleConfigPolicy.BLOCK_NEW_ASSIGNMENTS) =
        PrismClientOptions(configSyncInterval = Duration.ofDays(1), eventFlushInterval = Duration.ofDays(1),
            initializationTimeout = Duration.ofSeconds(2), flushTimeout = Duration.ofSeconds(2),
            shutdownTimeout = Duration.ofMillis(200), maxConfigAge = maxAge, staleConfigPolicy = policy)

    private fun create(options: PrismClientOptions = options(), initialized: Boolean = true,
                       requestTimeout: Duration = Duration.ofSeconds(5), http: HttpClient = HttpClient.newHttpClient()): LocalEvaluationClient {
        return LocalEvaluationClient(server.url("/").toString().trimEnd('/'), requestTimeout, options,
            http, time, time.nanos::get).also {
            local = it
            if (initialized) {
                assertEquals("A", it.evaluate("bootstrap", "checkout", emptyMap()).variant)
                assertTrue(it.refreshConfig()) // Wait for the initial worker and establish a conditional confirmation.
            }
        }
    }

    @AfterEach fun stop() {
        configGate?.countDown(); eventGate?.countDown(); streamPermit.countDown()
        local?.close(); server.shutdown()
    }

    @Test fun `uninitialized and unbounded settings are not reported as fresh`() {
        configGate = CountDownLatch(1)
        val client = create(options(null, StaleConfigPolicy.KEEP_LAST).copy(initializationTimeout = Duration.ZERO), false)
        val initial = client.diagnostics
        assertEquals(ConfigurationStatus.NOT_INITIALIZED, initial.configuration!!.status)
        assertNull(initial.configuration.age)
        assertNull(initial.configuration.lastSuccessAt)
        configGate!!.countDown()
        assertTrue(client.refreshConfig())
        time.advance(Duration.ofDays(100))
        configReply.set(ConfigReply.HTTP_FAILURE)
        assertFalse(client.refreshConfig())
        val observed = client.diagnostics.configuration!!
        assertEquals(ConfigurationStatus.UNBOUNDED, observed.status)
        assertEquals(Duration.ofDays(100), observed.age)
        assertEquals(ConfigSyncResult.FAILED, observed.lastSyncResult)
        assertEquals("A", client.assign("u", "checkout", emptyMap()).variant)
        assertEquals(ConfigurationStatus.NOT_INITIALIZED, initial.configuration.status)
    }

    @Test fun `expiration blocks evaluation deferred exposure and population exposure but preserves prior conversions`() {
        val client = create()
        val prior = client.assign("old", "checkout", emptyMap())
        val prepared = client.evaluate("prepared", "checkout", emptyMap())
        assertTrue(client.recordPopulationExposure("old"))
        time.advance(Duration.ofMinutes(5).minusNanos(1))
        assertEquals("A", client.evaluate("before", "checkout", emptyMap()).variant)
        time.advance(Duration.ofNanos(1))
        assertEquals("9996", client.assign("new", "checkout", emptyMap()).resultCode)
        assertFalse(client.recordExposure(prepared))
        assertFalse(client.recordPopulationExposure("new"))
        assertNull(client.isInHoldout("new"))
        assertTrue(client.trackConversion(prior, "purchase"))
        assertTrue(client.trackConversion("old", "checkout", "purchase", Duration.ofDays(1)))
        assertTrue(client.trackPopulationConversion("old", "purchase"))
        assertTrue(client.flush())
        assertEquals(3, client.diagnostics.configuration!!.staleRejectedCount)
        assertEquals(ConfigurationStatus.STALE, client.diagnostics.configuration!!.status)
        assertTrue(batches.flatten().none { it.userId == "new" || it.userId == "prepared" })
    }

    @Test fun `conditional confirmation renews freshness and resumes assignments`() {
        val client = create()
        time.advance(Duration.ofMinutes(6))
        assertEquals("9996", client.evaluate("u", "checkout", emptyMap()).resultCode)
        assertTrue(client.refreshConfig())
        val state = client.diagnostics.configuration!!
        assertEquals(Duration.ZERO, state.age)
        assertEquals(time.instant(), state.lastSuccessAt)
        assertEquals(ConfigurationStatus.FRESH, state.status)
        assertEquals("A", client.assign("u", "checkout", emptyMap()).variant)
    }

    @Test fun `keep last can observe expiration without changing assignment behavior`() {
        val client = create(options(policy = StaleConfigPolicy.KEEP_LAST))
        time.advance(Duration.ofMinutes(6))
        assertEquals(ConfigurationStatus.STALE, client.diagnostics.configuration!!.status)
        assertEquals("A", client.assign("u", "checkout", emptyMap()).variant)
        assertEquals(0, client.diagnostics.configuration!!.staleRejectedCount)
    }

    @Test fun `failures malformed data and outdated revisions never renew freshness`() {
        val client = create()
        config.set(configuration("b".repeat(64), 1))
        assertTrue(client.refreshConfig())
        val confirmed = client.diagnostics.configuration!!.lastSuccessAt
        time.advance(Duration.ofMinutes(4))
        configReply.set(ConfigReply.HTTP_FAILURE)
        assertFalse(client.refreshConfig())
        assertEquals(SdkDiagnosticFailure.HTTP_ERROR, client.diagnostics.configuration!!.lastFailure)
        assertEquals(503, client.diagnostics.configuration!!.lastFailureHttpStatus)
        configReply.set(ConfigReply.MALFORMED)
        assertFalse(client.refreshConfig())
        assertEquals(SdkDiagnosticFailure.INVALID_RESPONSE, client.diagnostics.configuration!!.lastFailure)
        assertNull(client.diagnostics.configuration!!.lastFailureHttpStatus)
        configReply.set(ConfigReply.OUTDATED)
        assertFalse(client.refreshConfig())
        val failed = client.diagnostics.configuration!!
        assertEquals(SdkDiagnosticFailure.OUTDATED_REVISION, failed.lastFailure)
        assertEquals(3, failed.consecutiveFailureCount)
        assertEquals(confirmed, failed.lastSuccessAt)
        time.advance(Duration.ofMinutes(1))
        assertEquals("9996", client.evaluate("u", "checkout", emptyMap()).resultCode)
        configReply.set(ConfigReply.NORMAL)
        assertTrue(client.refreshConfig())
        assertEquals(0, client.diagnostics.configuration!!.consecutiveFailureCount)
        assertEquals(ConfigSyncResult.SUCCESS, client.diagnostics.configuration!!.lastSyncResult)
    }

    @Test fun `unsolicited 304 cannot initialize a configuration`() {
        configReply.set(ConfigReply.UNCONDITIONAL_304)
        val client = create(options().copy(initializationTimeout = Duration.ZERO), false)
        assertFalse(client.refreshConfig())
        assertEquals(ConfigurationStatus.NOT_INITIALIZED, client.diagnostics.configuration!!.status)
        assertEquals(SdkDiagnosticFailure.INVALID_RESPONSE, client.diagnostics.configuration!!.lastFailure)
        assertNull(client.diagnostics.configuration!!.lastSuccessAt)
    }

    @Test fun `wall clock rollback does not extend configuration lifetime`() {
        val client = create()
        time.advance(Duration.ofMinutes(5))
        time.wall.updateAndGet { it.minus(Duration.ofDays(1)) }
        assertEquals(Duration.ofMinutes(5), client.diagnostics.configuration!!.age)
        assertEquals("9996", client.evaluate("u", "checkout", emptyMap()).resultCode)
    }

    @Test fun `empty active configuration recovers freshness without reviving paused experiments`() {
        val client = create()
        val prior = client.assign("old", "checkout", emptyMap())
        time.advance(Duration.ofMinutes(6))
        config.set(ConfigResponse("b".repeat(64), emptyList(), HoldoutConfig(configured = true), 1))
        assertTrue(client.refreshConfig())
        assertEquals(ConfigurationStatus.FRESH, client.diagnostics.configuration!!.status)
        assertNull(client.assign("new", "checkout", emptyMap()).variant)
        assertTrue(client.trackConversion(prior, "purchase"))
    }

    @Test fun `delivery distinguishes materialized duplicate queued and retry acknowledgements`() {
        val client = create()
        eventStatus.set { when (it.userId) {
            "accepted" -> EventStatus.ACCEPTED
            "duplicate" -> EventStatus.DUPLICATE
            "queued" -> EventStatus.QUEUED
            else -> EventStatus.RETRY
        } }
        listOf("accepted", "duplicate", "queued", "retry").forEach { assertNotNull(client.assign(it, "checkout", emptyMap()).variant) }
        time.advance(Duration.ofSeconds(10))
        assertFalse(client.flush())
        val first = client.diagnostics.events!!
        assertEquals(4, first.enqueuedCount)
        assertEquals(1, first.acceptedCount)
        assertEquals(1, first.duplicateCount)
        assertEquals(1, first.durablyQueuedCount)
        assertEquals(1, first.retryResponseCount)
        assertEquals(1, first.pendingCount)
        assertEquals(Duration.ofSeconds(10), first.oldestPendingAge)
        assertEquals(BatchDeliveryStatus.ACKNOWLEDGED, first.lastBatchStatus)
        eventStatus.set { EventStatus.ACCEPTED }
        assertTrue(client.flush())
        assertEquals(2, client.diagnostics.events!!.acceptedCount)
        assertNull(client.diagnostics.events!!.oldestPendingAge)
        assertEquals(1, first.acceptedCount) // Returned diagnostics remain immutable.
        assertEquals(batches[0].last().eventId, batches[1].single().eventId)
    }

    @Test fun `failed and incomplete ACKs retain original IDs and do not count successful events`() {
        val client = create()
        assertNotNull(client.assign("private-user", "checkout", emptyMap()).variant)
        eventReply.set(EventReply.HTTP_FAILURE)
        assertFalse(client.flush())
        assertEquals(SdkDiagnosticFailure.HTTP_ERROR, client.diagnostics.events!!.lastFailure)
        assertEquals(500, client.diagnostics.events!!.lastFailureHttpStatus)
        eventReply.set(EventReply.INCOMPLETE)
        assertFalse(client.flush())
        val failed = client.diagnostics.events!!
        assertEquals(2, failed.failedBatchCount)
        assertEquals(0, failed.acceptedCount)
        assertEquals(1, failed.pendingCount)
        assertEquals(SdkDiagnosticFailure.INVALID_RESPONSE, failed.lastFailure)
        assertNull(failed.lastFailureHttpStatus)
        eventReply.set(EventReply.NORMAL)
        eventStatus.set { EventStatus.DUPLICATE }
        assertTrue(client.flush())
        assertEquals(1, client.diagnostics.events!!.duplicateCount)
        assertEquals(1, batches.map { it.single().eventId }.distinct().size)
        assertFalse(client.diagnostics.toString().contains("private-user"))
    }

    @Test fun `dependent discards are separate from server rejections`() {
        val client = create()
        val assignment = client.assign("u", "checkout", emptyMap())
        assertTrue(client.trackConversion(assignment, "purchase"))
        eventStatus.set { if (it.type == "exposure") EventStatus.REJECTED else EventStatus.RETRY }
        assertFalse(client.flush())
        val state = client.diagnostics.events!!
        assertEquals(1, state.rejectedCount)
        assertEquals(1, state.discardedDependentCount)
        assertEquals(1, state.retryResponseCount)
        assertEquals(0, state.pendingCount)
        assertNull(state.oldestPendingAge)
        assertFalse(client.trackConversion(assignment, "purchase"))
    }

    @Test fun `queue capacity rejection is visible and retries preserve original age`() {
        val client = create(options().copy(eventQueueCapacity = 100))
        eventStatus.set { EventStatus.RETRY }
        // Use a full queue while RETRY prevents the automatic flusher from removing any entry.
        repeat(100) { assertNotNull(client.assign("u$it", "checkout", emptyMap()).variant) }
        assertNull(client.assign("overflow", "checkout", emptyMap()).variant)
        time.advance(Duration.ofSeconds(10))
        assertFalse(client.flush())
        val state = client.diagnostics.events!!
        assertEquals(100, state.pendingCount)
        assertEquals(1, state.capacityRejectedCount)
        assertEquals(Duration.ofSeconds(10), state.oldestPendingAge)
    }

    @Test fun `diagnostics remain accessible while delivery is in flight and after close`() {
        val client = create()
        assertNotNull(client.assign("u", "checkout", emptyMap()).variant)
        eventGate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Boolean> { client.flush() }
            assertTrue(eventsEntered.await(2, TimeUnit.SECONDS))
            assertEquals(BatchDeliveryStatus.IN_FLIGHT, client.diagnostics.events!!.lastBatchStatus)
            eventGate!!.countDown()
            assertTrue(future.get(3, TimeUnit.SECONDS))
        } finally { eventGate!!.countDown(); pool.shutdownNow() }
        val before = client.diagnostics
        client.close()
        assertTrue(client.diagnostics.closed)
        assertEquals(1, client.diagnostics.events!!.acceptedCount)
        assertFalse(before.closed)
    }

    @Test fun `closed clients retain observable undelivered queue without accepting new events`() {
        val client = create()
        val assignment = client.assign("u", "checkout", emptyMap())
        eventReply.set(EventReply.HTTP_FAILURE)
        client.close()
        assertTrue(client.diagnostics.closed)
        assertEquals(1, client.diagnostics.events!!.pendingCount)
        assertFalse(client.recordExposure(assignment))
        assertFalse(client.trackConversion(assignment, "purchase"))
        assertEquals(0, client.diagnostics.events!!.capacityRejectedCount)
    }

    @Test fun `remote diagnostics mark local components as unavailable`() {
        val client = PrismClient(server.url("/").toString(), options = PrismClientOptions(evaluationMode = EvaluationMode.REMOTE))
        assertEquals(EvaluationMode.REMOTE, client.diagnostics.evaluationMode)
        assertNull(client.diagnostics.configuration)
        assertNull(client.diagnostics.events)
        assertNull(client.diagnostics.exposureDedup)
        client.close()
        assertTrue(client.diagnostics.closed)
        assertEquals(0, server.requestCount)
    }

    @Test fun `blocking requires a positive representable maximum age`() {
        assertThrows(IllegalArgumentException::class.java) { PrismClientOptions(staleConfigPolicy = StaleConfigPolicy.BLOCK_NEW_ASSIGNMENTS) }
        listOf(Duration.ZERO, Duration.ofNanos(-1), Duration.ofSeconds(Long.MAX_VALUE)).forEach { age ->
            assertThrows(IllegalArgumentException::class.java) { PrismClientOptions(maxConfigAge = age) }
        }
    }

    @Test fun `a request timeout reports TIMEOUT rather than a wrapper exception`() {
        val client = create(requestTimeout = Duration.ofMillis(500))
        assertNotNull(client.assign("u", "checkout", emptyMap()).variant)
        eventReply.set(EventReply.NO_RESPONSE)
        assertFalse(client.flush())
        assertEquals(SdkDiagnosticFailure.TIMEOUT, client.diagnostics.events!!.lastFailure)
        assertEquals(1, client.diagnostics.events!!.pendingCount)
        assertEquals(0, client.diagnostics.events!!.acceptedCount)
        eventReply.set(EventReply.NORMAL)
        assertTrue(client.flush())
    }

    @Test fun `wrapped request timeouts remain TIMEOUT in delivery diagnostics`() {
        val delegate = HttpClient.newHttpClient()
        val transport = mockk<HttpClient>()
        every { transport.sendAsync(any<HttpRequest>(), any<HttpResponse.BodyHandler<String>>()) } answers {
            val request = firstArg<HttpRequest>()
            if (request.uri().path == "/v1/events") CompletableFuture.failedFuture<HttpResponse<String>>(
                HttpTimeoutException("transport detail must not leak"))
            else delegate.sendAsync(request, secondArg<HttpResponse.BodyHandler<String>>())
        }
        every { transport.shutdownNow() } answers { delegate.shutdownNow() }
        val client = create(http = transport)
        assertNotNull(client.assign("u", "checkout", emptyMap()).variant)
        assertFalse(client.flush())
        assertEquals(SdkDiagnosticFailure.TIMEOUT, client.diagnostics.events!!.lastFailure)
        assertEquals(1, client.diagnostics.events!!.pendingCount)
        assertFalse(client.diagnostics.toString().contains("transport detail"))
    }

    @Test fun `public facade and supported strategy assignments respect expiration while attributed conversions continue`() {
        PrismClient(server.url("/").toString(), options = options().copy(maxConfigAge = Duration.ofSeconds(1))).use { client ->
            val experiments = PrismExperimentClient(client)
            val prior = experiments.assign("old", "checkout")
            assertTrue(prior.assigned)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (client.diagnostics.configuration!!.status != ConfigurationStatus.STALE && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals("9996", experiments.assign("new", "checkout").resultCode)
            assertEquals("9996", experiments.assignSupported("strategy", "checkout", setOf("A")).resultCode)
            assertTrue(experiments.track(prior, "purchase"))
            assertTrue(experiments.trackIfAssigned("old", "checkout", "purchase"))
            assertTrue(client.flush())
        }
    }

    @Test fun `an older conditional response cannot renew a newer streamed configuration`() {
        val client = create(options().copy(configStreaming = true))
        assertTrue(streamEntered.await(2, TimeUnit.SECONDS))
        configGate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val future = pool.submit<Boolean> { client.refreshConfig() }
            assertTrue(configEntered.await(2, TimeUnit.SECONDS))
            time.advance(Duration.ofMinutes(1))
            streamPermit.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
            while (client.diagnostics.configuration!!.revision != 1L && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, client.diagnostics.configuration!!.revision)
            time.advance(Duration.ofMinutes(5))
            configGate!!.countDown()
            assertTrue(future.get(3, TimeUnit.SECONDS))
            assertEquals(Duration.ofMinutes(5), client.diagnostics.configuration!!.age)
            assertEquals("9996", client.evaluate("u", "checkout", emptyMap()).resultCode)
        } finally { configGate!!.countDown(); pool.shutdownNow() }
    }
}
