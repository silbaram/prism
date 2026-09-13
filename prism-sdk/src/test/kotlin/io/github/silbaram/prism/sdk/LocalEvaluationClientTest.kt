package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.config.*
import io.github.silbaram.prism.common.rest.dto.event.*
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class LocalEvaluationClientTest {
    private val mapper = jacksonObjectMapper()
    private val server = MockWebServer()
    private val configRequests = AtomicInteger()
    private val remoteRequests = AtomicInteger()
    private val eventRequests = CopyOnWriteArrayList<EventsRequest>()
    private val committedIds = ConcurrentHashMap.newKeySet<String>()
    private val failConfig = AtomicBoolean()
    private val loseEventResponse = AtomicBoolean()
    private val malformedAck = AtomicBoolean()
    private val rejectExposure = AtomicBoolean()
    private val retryUser = AtomicReference<String?>()
    private val delayEventBody = AtomicBoolean()
    private val etags = CopyOnWriteArrayList<String>()
    private val eventArrived = CountDownLatch(1)
    @Volatile private var eventGate: CountDownLatch? = null
    @Volatile private var configGate: CountDownLatch? = null
    private val configBlocked = CountDownLatch(1)
    private val config = AtomicReference(configuration())
    private lateinit var client: PrismClient

    private fun configuration(version: String = "a".repeat(64)) = ConfigResponse(version,
        listOf(ExperimentConfig("checkout", "ACTIVE", listOf(VariantConfig("A", 100)), listOf("age >= 20 && country == 'KR'"))))

    @BeforeEach
    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v1/assignments" -> MockResponse().setBody("""{"userId":"u","experimentKey":"checkout","variant":null,"resultCode":"9100","resultMessage":"No exposure"}""")
                "/v1/config" -> {
                    configRequests.incrementAndGet()
                    configGate?.let { configBlocked.countDown(); it.await(2, TimeUnit.SECONDS) }
                    request.getHeader("If-None-Match")?.let { etags.add(it) }
                    val current = config.get()
                    val tag = "\"${current.version}\""
                    when {
                        failConfig.get() -> MockResponse().setResponseCode(503)
                        request.getHeader("If-None-Match") == tag -> MockResponse().setResponseCode(304).setHeader("ETag", tag)
                        else -> MockResponse().setHeader("ETag", tag).setBody(mapper.writeValueAsString(current))
                    }
                }
                "/v1/events" -> {
                    val events = mapper.readValue<EventsRequest>(request.body.readUtf8())
                    eventRequests.add(events)
                    eventArrived.countDown()
                    eventGate?.await(2, TimeUnit.SECONDS)
                    val result = events.events.map { event ->
                        val status = when {
                            event.userId == retryUser.get() -> EventStatus.RETRY
                            rejectExposure.get() -> if (event.type == "exposure") EventStatus.REJECTED else EventStatus.RETRY
                            committedIds.add(event.eventId) -> EventStatus.ACCEPTED
                            else -> EventStatus.DUPLICATE
                        }
                        EventResult(event.eventId, status)
                    }
                    when {
                        loseEventResponse.getAndSet(false) -> MockResponse().setResponseCode(500)
                        malformedAck.get() -> MockResponse().setBody("{\"results\":[]}")
                        else -> MockResponse().setBody(mapper.writeValueAsString(EventsResponse(result)))
                            .setBodyDelay(if (delayEventBody.get()) 3 else 0, TimeUnit.SECONDS)
                    }
                }
                else -> { remoteRequests.incrementAndGet(); MockResponse().setResponseCode(404) }
            }
        }
        server.start()
    }

    private fun create(options: PrismClientOptions = PrismClientOptions(
        configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
        initializationTimeout = Duration.ofSeconds(1), shutdownTimeout = Duration.ofSeconds(1)
    )): PrismClient = PrismClient(server.url("/").toString(), Duration.ofSeconds(1), options).also { client = it }

    @AfterEach
    fun stop() {
        eventGate?.countDown()
        configGate?.countDown()
        if (::client.isInitialized) client.close()
        server.shutdown()
    }

    @Test
    fun `local targeting and repeated assignments perform no request except config sync and flush`() {
        create()
        assertNull(client.evaluate("u", "checkout", mapOf("age" to 17, "country" to "KR")).variant)
        assertEquals("A", client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        assertEquals(0, client.pendingEventCount)
        assertFalse(client.trackConversion("u", "checkout", "purchase"))
        val wrapper = PrismExperimentClient(client)
        repeat(20) { assertTrue(wrapper.assign("u", "checkout", mapOf("age" to 25, "country" to "KR")).assigned) }
        assertEquals(1, configRequests.get())
        assertEquals(0, remoteRequests.get())
        assertTrue(eventRequests.isEmpty())
        assertEquals(1, client.pendingEventCount)
        assertTrue(wrapper.trackIfAssigned("u", "checkout", "purchase"))
        assertTrue(client.flush())
        val events = eventRequests.single().events
        assertEquals(2, events.size)
        assertEquals(events.first().eventId, events.last().exposureEventId)
    }

    @Test
    fun `cached configuration stops evaluation at its deadline during a config outage`() {
        val end = java.time.Instant.now().plusSeconds(2)
        config.set(ConfigResponse("a".repeat(64), listOf(ExperimentConfig("checkout", "ACTIVE",
            listOf(VariantConfig("A", 100)), endsAt = end.toString()))))
        create()
        assertEquals("A", client.evaluate("u", "checkout").variant)
        failConfig.set(true)
        assertFalse(client.refreshConfig())
        val requests = configRequests.get()
        while (java.time.Instant.now() < end) Thread.sleep(20)
        assertNull(client.assign("u", "checkout").variant)
        assertEquals(0, client.pendingEventCount)
        assertEquals(requests, configRequests.get())
    }

    @Test
    fun `lifetime dedup capacity rejects new identities without evicting prior exposures`() {
        create(PrismClientOptions(exposureDedupCapacity = 1, configSyncInterval = Duration.ofHours(1),
            eventFlushInterval = Duration.ofHours(1)))
        val attributes = mapOf("age" to 25, "country" to "KR")
        val first = client.assign("u", "checkout", attributes)
        assertTrue(client.flush())
        assertNull(client.assign("new", "checkout", attributes).variant)
        assertEquals(first.exposureEventId, client.assign("u", "checkout", attributes).exposureEventId)
        assertEquals(0, client.pendingEventCount)
        assertTrue(client.trackConversion(first, "purchase"))
        assertTrue(client.flush())
    }

    @Test
    fun `dedup survives acknowledgement cache pressure and unrelated configuration changes`() {
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            exposureCacheMaximumSize = 1))
        val attributes = mapOf("age" to 25, "country" to "KR")
        val first = client.assign("u", "checkout", attributes)
        assertTrue(client.flush())
        repeat(10) { assertNotNull(client.assign("other-$it", "checkout", attributes).variant) }
        assertTrue(client.flush())
        config.set(configuration("b".repeat(64)))
        assertTrue(client.refreshConfig())
        val repeated = client.assign("u", "checkout", attributes)
        assertEquals(first.exposureEventId, repeated.exposureEventId)
        assertEquals(first.configVersion, repeated.configVersion)
        assertTrue(client.recordExposure(client.evaluate("u", "checkout", attributes)))
        assertEquals(0, client.pendingEventCount)
        assertTrue(client.trackConversion(repeated, "purchase"))
        assertTrue(client.flush())
        val all = eventRequests.flatMap { it.events }
        assertEquals(1, all.count { it.type == "exposure" && it.userId == "u" })
        assertEquals(first.exposureEventId, all.last().exposureEventId)
        assertEquals(first.configVersion, all.last().configVersion)
    }

    @Test
    fun `concurrent actual exposure registration emits one event and rejected exposure can be retried`() {
        create()
        val evaluated = client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        val workers = Executors.newFixedThreadPool(8)
        try {
            val results = (1..80).map { workers.submit<Boolean> { client.recordExposure(evaluated) } }
            assertTrue(results.all { it.get(3, TimeUnit.SECONDS) })
            assertEquals(1, client.pendingEventCount)
        } finally { workers.shutdownNow() }
        rejectExposure.set(true)
        assertFalse(client.flush())
        rejectExposure.set(false)
        assertTrue(client.recordExposure(evaluated))
        assertEquals(1, client.pendingEventCount)
        assertTrue(client.flush())
        assertNotEquals(eventRequests.first().events.single().eventId, eventRequests.last().events.single().eventId)
    }

    @Test
    fun `full queue allows already recorded exposure but does not remember failed admissions`() {
        create(PrismClientOptions(eventBatchSize = 1, eventQueueCapacity = 1,
            configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1)))
        retryUser.set("u")
        val attributes = mapOf("age" to 25, "country" to "KR")
        val first = client.assign("u", "checkout", attributes)
        assertEquals(first.exposureEventId, client.assign("u", "checkout", attributes).exposureEventId)
        assertNull(client.assign("new", "checkout", attributes).variant)
        retryUser.set(null)
        assertTrue(client.flush())
        assertNotNull(client.assign("new", "checkout", attributes).exposureEventId)
    }

    @Test
    fun `etag failures and malformed config preserve last snapshot while empty config removes experiments`() {
        create()
        fun evaluate() = client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant
        assertEquals("A", evaluate())
        assertTrue(client.refreshConfig())
        assertEquals("\"${"a".repeat(64)}\"", etags.last())
        failConfig.set(true)
        assertFalse(client.refreshConfig())
        assertEquals("A", evaluate())
        failConfig.set(false)
        config.set(ConfigResponse("b".repeat(64), listOf(ExperimentConfig("checkout", "ACTIVE", listOf(VariantConfig("B", 90))))))
        assertFalse(client.refreshConfig())
        assertEquals("A", evaluate())
        config.set(ConfigResponse("c".repeat(64), emptyList()))
        assertTrue(client.refreshConfig())
        assertNull(evaluate())
    }

    @Test
    fun `retry after lost response preserves payload ids and acknowledges duplicates`() {
        create()
        val assignment = client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        assertTrue(client.recordExposure(assignment))
        assertTrue(client.trackConversion("u", "checkout", "purchase"))
        loseEventResponse.set(true)
        assertFalse(client.flush())
        assertEquals(2, client.pendingEventCount)
        assertTrue(client.flush())
        assertEquals(eventRequests[0], eventRequests[1])
        assertEquals(2, committedIds.size)
        assertEquals(0, client.pendingEventCount)
    }

    @Test
    fun `incomplete acknowledgements retain every event until a valid response arrives`() {
        create()
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        malformedAck.set(true)
        assertFalse(client.flush())
        assertEquals(1, client.pendingEventCount)
        malformedAck.set(false)
        assertTrue(client.flush())
        assertEquals(1, committedIds.size)
    }

    @Test
    fun `batch size triggers delivery and queue rejects overflow without creating an exposure`() {
        eventGate = CountDownLatch(1)
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            eventBatchSize = 2, eventQueueCapacity = 2, shutdownTimeout = Duration.ofSeconds(1)))
        assertEquals("A", client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        assertTrue(client.trackConversion("u", "checkout", "purchase"))
        assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
        assertNull(client.assign("overflow", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        assertNull(client.getAssignment("overflow", "checkout").variant)
        assertFalse(client.trackConversion("overflow", "checkout", "purchase"))
        eventGate!!.countDown()
        assertTrue(client.flush())
    }

    @Test
    fun `zero initialization wait fails safely and later synchronization recovers`() {
        failConfig.set(true)
        create(PrismClientOptions(initializationTimeout = Duration.ZERO, configSyncInterval = Duration.ofHours(1)))
        assertNull(client.assign("u", "checkout").variant)
        assertEquals(0, client.pendingEventCount)
        failConfig.set(false)
        assertTrue(client.refreshConfig())
        assertEquals("A", client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
    }

    @Test
    fun `closing flushes queued events and prevents later assignments and tracking`() {
        create()
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        client.close()
        assertEquals(1, committedIds.size)
        assertNull(client.assign("u", "checkout").variant)
        assertFalse(client.trackConversion("u", "checkout", "purchase"))
        assertFalse(client.refreshConfig())
    }

    @Test
    fun `periodic event delivery continues while a background config request is blocked`() {
        create(PrismClientOptions(configSyncInterval = Duration.ofMillis(25), eventFlushInterval = Duration.ofMillis(50)))
        assertEquals("A", client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        configGate = CountDownLatch(1)
        assertTrue(configBlocked.await(2, TimeUnit.SECONDS))
        assertEquals("A", client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
        assertEquals(1L, configGate!!.count)
        configGate!!.countDown()
        assertTrue(client.flush())
    }

    @Test
    fun `close respects its total timeout when the collector does not respond`() {
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            shutdownTimeout = Duration.ofMillis(100)))
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        eventGate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            pool.submit { client.close() }.get(2, TimeUnit.SECONDS)
            assertEquals(1, client.pendingEventCount)
        } finally { eventGate!!.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `concurrent close does not cancel the first callers final delivery`() {
        create()
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        eventGate = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val closing = pool.submit { client.close() }
            assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
            val secondStarted = CountDownLatch(1)
            val second = pool.submit { secondStarted.countDown(); client.close() }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { second.get(50, TimeUnit.MILLISECONDS) }
            eventGate!!.countDown()
            closing.get(2, TimeUnit.SECONDS)
            second.get(2, TimeUnit.SECONDS)
            assertEquals(0, client.pendingEventCount)
        } finally { eventGate!!.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `close timeout includes a stalled response body after headers arrive`() {
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            shutdownTimeout = Duration.ofMillis(100)))
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        delayEventBody.set(true)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val closing = pool.submit { client.close() }
            assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
            closing.get(1, TimeUnit.SECONDS)
            assertEquals(1, client.pendingEventCount)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `explicit conversion requires a real exposure reference and preserves its ID`() {
        create()
        val evaluated = client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        assertFalse(client.trackConversion(evaluated, "purchase"))
        val assigned = client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        assertNotNull(assigned.exposureEventId)
        assertFalse(client.trackConversion(assigned.copy(exposureEventId = "invalid"), "purchase"))
        assertTrue(client.trackConversion(assigned, "purchase"))
        assertTrue(client.flush())
        assertEquals(assigned.exposureEventId, eventRequests.single().events.last().exposureEventId)
    }

    @Test
    fun `explicit outcome tracking keeps the original exposure after another assignment`() {
        create()
        val wrapper = PrismExperimentClient(client)
        val original = wrapper.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        assertEquals("A", original.variant)
        config.set(ConfigResponse("b".repeat(64), listOf(ExperimentConfig("checkout", "ACTIVE", listOf(VariantConfig("B", 100))))))
        assertTrue(client.refreshConfig())
        assertEquals("B", wrapper.assign("u", "checkout").variant)
        assertTrue(wrapper.track(original, "purchase"))
        assertTrue(wrapper.trackIfAssigned("u", "checkout", "purchase"))
        assertTrue(client.flush())
        val events = eventRequests.single().events
        assertEquals(listOf("A", "B"), events.filter { it.type == "conversion" }.map { it.variant })
        assertEquals(events.first().eventId, events[2].exposureEventId)
        assertEquals(events[1].eventId, events[3].exposureEventId)
    }

    @Test
    fun `retrying one event does not prevent later batches from being delivered`() {
        retryUser.set("retry")
        eventGate = CountDownLatch(1)
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            eventBatchSize = 2, shutdownTimeout = Duration.ofSeconds(2)))
        fun assign(user: String) = client.assign(user, "checkout", mapOf("age" to 25, "country" to "KR"))
        assertNotNull(assign("retry").variant)
        assertNotNull(assign("healthy-1").variant)
        assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
        assertNotNull(assign("healthy-2").variant)
        assertNotNull(assign("healthy-3").variant)
        eventGate!!.countDown()
        assertFalse(client.flush())
        assertEquals(1, client.pendingEventCount)
        assertEquals(3, committedIds.size)
        val originalId = eventRequests.first().events.first().eventId
        retryUser.set(null)
        assertTrue(client.flush())
        assertEquals(originalId, eventRequests.last().events.single().eventId)
        assertEquals(4, committedIds.size)
    }

    @Test
    fun `rejected exposure removes dependent conversions and cannot be reused for tracking`() {
        create()
        val assignment = client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        client.trackConversion("u", "checkout", "purchase")
        rejectExposure.set(true)
        assertFalse(client.flush())
        assertEquals(0, client.pendingEventCount)
        assertFalse(client.trackConversion("u", "checkout", "purchase"))
        assertFalse(client.trackConversion(assignment, "purchase"))
        assertEquals(0, client.pendingEventCount)
    }
}
