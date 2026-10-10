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
    private val queueEvents = AtomicBoolean()
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
                            queueEvents.get() -> EventStatus.QUEUED
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
    fun `complete registry preserves normal SDK targeting analysis and deferred exposure`() {
        create()
        // The registry is complete even though this user lacks required targeting attributes.
        assertNull(client.assignSupported("registry-user", "checkout", setOf("A")).variant)
        val attributes = mapOf("age" to 25, "country" to "KR")
        val analysis = ExposureAnalysisContext(mapOf("device" to "mobile"))
        val assignment = client.assign("assigned-user", "checkout", attributes, analysis)
        assertEquals("A", assignment.variant)
        assertNull(client.evaluate("ineligible-user", "checkout", attributes + ("age" to 17)).variant)
        val prepared = client.evaluate("prepared-user", "checkout", attributes)
        assertEquals("A", prepared.variant)
        assertTrue(client.recordExposure(prepared))
        assertTrue(client.trackConversion(assignment, "purchase"))
        assertTrue(client.flush())
        val events = eventRequests.flatMap { it.events }
        assertEquals(2, events.count { it.type == "exposure" })
        assertEquals(1, events.count { it.type == "conversion" })
        assertEquals(analysis, events.first { it.userId == "assigned-user" && it.type == "exposure" }.analysis)
    }

    @Test
    fun `strategy restrictions also guard pure evaluation and deferred exposure registration`() {
        config.set(ConfigResponse("a".repeat(64), listOf(ExperimentConfig("checkout", "ACTIVE",
            listOf(VariantConfig("A", 50), VariantConfig("B", 50))))))
        create()
        val prepared = client.evaluate("prepared-user", "checkout")
        assertNotNull(prepared.variant)
        assertNull(client.assignSupported("registry-user", "checkout", setOf("A")).variant)
        assertFalse(client.recordExposure(prepared))
        assertNull(client.evaluate("next-user", "checkout").variant)
        assertNull(client.assign("next-user", "checkout").variant)
        assertEquals(0, client.pendingEventCount)
        assertTrue(client.flush())
        assertTrue(eventRequests.isEmpty())
    }

    @Test
    fun `invalid strategy names block old local exposures instead of restoring unrestricted tracking`() {
        config.set(ConfigResponse("a".repeat(64), listOf(ExperimentConfig("checkout", "ACTIVE", listOf(VariantConfig("A", 100))))))
        create()
        val previous = client.assign("returning-user", "checkout")
        assertNotNull(previous.variant)
        assertNull(client.assignSupported("new-user", "checkout", setOf(" ")).variant)
        assertFalse(client.trackConversion(previous, "purchase"))
        assertFalse(client.trackConversion("returning-user", "checkout", "purchase"))
        assertEquals(1, client.pendingEventCount)
    }

    @Test
    fun `incomplete local registry suppresses exposure and both conversion entry points`() {
        config.set(ConfigResponse("a".repeat(64), listOf(ExperimentConfig("checkout", "ACTIVE",
            listOf(VariantConfig("A", 50), VariantConfig("B", 50))))))
        create()
        val previous = client.assign("returning-user", "checkout")
        assertNotNull(previous.variant)
        assertTrue(client.flush())
        repeat(12) { assertNull(client.assignSupported("user-$it", "checkout", setOf("A")).variant) }
        assertFalse(client.trackConversion(previous, "purchase"))
        assertFalse(client.trackConversion("returning-user", "checkout", "purchase"))
        assertEquals(0, client.pendingEventCount)
        assertTrue(client.flush())
        assertEquals(1, eventRequests.flatMap { it.events }.size)
        assertEquals("exposure", eventRequests.single().events.single().type)
        assertEquals(0, remoteRequests.get())
    }

    @Test
    fun `complete local registry allows conversions after pause but rejects historical unsupported sticky arm`() {
        val definition = ExperimentConfig("checkout", "ACTIVE",
            listOf(VariantConfig("A", 100), VariantConfig("B", 0)), stickyBucketing = true)
        config.set(ConfigResponse("a".repeat(64), listOf(definition)))
        val store = InMemoryStickyAssignmentStore().apply { getOrPut("stale-user", "checkout", "B") }
        create(PrismClientOptions(stickyAssignmentStore = store, configSyncInterval = Duration.ofHours(1),
            eventFlushInterval = Duration.ofHours(1)))
        val stale = client.assign("stale-user", "checkout")
        assertEquals("B", stale.variant)
        val valid = client.assignSupported("valid-user", "checkout", setOf("A"))
        assertEquals("A", valid.variant)
        assertNull(client.assignSupported("stale-user", "checkout", setOf("A")).variant)
        assertFalse(client.trackConversion(stale, "purchase"))
        assertFalse(client.trackConversion("stale-user", "checkout", "purchase"))
        config.set(ConfigResponse("b".repeat(64), emptyList(), revision = 1))
        assertTrue(client.refreshConfig())
        assertTrue(client.trackConversion(valid, "purchase"))
        assertTrue(client.flush())
        val events = eventRequests.flatMap { it.events }
        assertEquals(2, events.count { it.type == "exposure" })
        assertEquals(listOf("valid-user"), events.filter { it.type == "conversion" }.map { it.userId })
    }

    @Test
    fun `analysis metadata is explicit frozen with first exposure and absent from conversions`() {
        create()
        val segments = mutableMapOf("device" to "mobile")
        val context = ExposureAnalysisContext(segments, 42.0, "2026-09-01T00:00:00Z")
        val attributes = mapOf("age" to 25, "country" to "KR", "privateAttribute" to "not-transmitted")
        val first = client.assign("u", "checkout", attributes, context)
        assertNotNull(first.exposureEventId)
        segments["device"] = "desktop"
        val repeated = client.assign("u", "checkout", attributes, context.copy(baselineValue = 999.0))
        assertEquals(first.exposureEventId, repeated.exposureEventId)
        assertTrue(client.trackConversion(first, "purchase"))
        assertTrue(client.flush())
        val events = eventRequests.single().events
        assertEquals(ExposureAnalysisContext(mapOf("device" to "mobile"), 42.0, "2026-09-01T00:00:00Z"), events.first().analysis)
        assertNull(events.last().analysis)
        assertFalse(mapper.writeValueAsString(events).contains("privateAttribute"))
        assertNull(client.assign("invalid", "checkout", attributes, context.copy(baselineValue = Double.NaN)).variant)
        assertNull(client.assign("invalid-time", "checkout", attributes,
            context.copy(baselineMeasuredAt = "+1000000000-01-01T00:00:00Z")).variant)
    }

    @Test
    fun `events without analysis metadata preserve the pre-upgrade canonical payload`() {
        val event = ClientEvent("10000000-0000-0000-0000-000000000001", "exposure", "u", "checkout", "A",
            "2026-09-13T00:00:00Z", "a".repeat(64))
        val legacy = """{"eventId":"10000000-0000-0000-0000-000000000001","type":"exposure","userId":"u","experimentKey":"checkout","variant":"A","timestamp":"2026-09-13T00:00:00Z","configVersion":"${"a".repeat(64)}","eventName":null,"exposureEventId":null}"""
        assertEquals(legacy, mapper.writeValueAsString(event))
    }

    @Test
    fun `queued exposure remains available before asynchronous materialization despite expired lookup cache`() {
        queueEvents.set(true)
        create()
        val assignment = client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        assertNotNull(assignment.exposureEventId)
        assertTrue(client.flush())
        assertEquals(0, client.pendingEventCount)
        assertTrue(client.trackConversion("u", "checkout", "purchase", Duration.ZERO))
        assertTrue(client.flush())
        assertEquals(assignment.exposureEventId, eventRequests.last().events.single().exposureEventId)
    }

    @Test
    fun `configuration revisions cannot regress and a configured holdout cannot be replaced`() {
        config.set(configuration().copy(revision = 2, holdout = HoldoutConfig("permanent", 0, true)))
        create()
        assertTrue(client.refreshConfig())
        assertEquals(false, client.isInHoldout("u"))
        val changed = ConfigResponse("b".repeat(64), emptyList(), HoldoutConfig("permanent", 0, true), 1)
        config.set(changed)
        assertFalse(client.refreshConfig())
        assertEquals("A", client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        config.set(changed.copy(revision = 3, holdout = HoldoutConfig("permanent", 500, true)))
        assertFalse(client.refreshConfig())
        config.set(changed.copy(revision = 3, holdout = HoldoutConfig()))
        assertFalse(client.refreshConfig())
        config.set(changed.copy(revision = 3))
        assertTrue(client.refreshConfig())
        assertNull(client.evaluate("u", "checkout").variant)
    }

    @Test
    fun `overlapping layer snapshots are rejected atomically`() {
        create()
        assertNotNull(client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        val first = configuration().experiments.single().copy(targetingRules = emptyList(), layer = LayerConfig("checkout", 0, 6000))
        config.set(ConfigResponse("b".repeat(64), listOf(first, first.copy(key = "other", layer = LayerConfig("checkout", 5000, 10000)))))
        assertFalse(client.refreshConfig())
        assertNull(client.evaluate("u", "other").variant)
        assertNotNull(client.evaluate("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        config.set(ConfigResponse("c".repeat(64), emptyList(), HoldoutConfig(basisPoints = 500)))
        assertFalse(client.refreshConfig())
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
    fun `capacity has a distinct assignment failure and boolean registration counts rejections`() {
        create(PrismClientOptions(exposureDedupCapacity = 1, configSyncInterval = Duration.ofHours(1),
            eventFlushInterval = Duration.ofHours(1)))
        val attributes = mapOf("age" to 25, "country" to "KR")
        assertEquals(0, client.exposureDedupCount)
        assertNotNull(client.assign("u", "checkout", attributes).variant)
        assertTrue(client.flush())
        assertEquals(1, client.exposureDedupCount)
        assertEquals(0, client.pendingEventCount)
        val rejected = client.assign("new", "checkout", attributes)
        assertEquals(SdkResponseCode.EXPOSURE_DEDUP_CAPACITY_REACHED.code, rejected.resultCode)
        assertEquals("Exposure deduplication capacity reached", rejected.resultMessage)
        assertFalse(AssignmentOutcome.from(rejected).assigned)
        assertNull(rejected.variant)
        assertNull(rejected.exposureEventId)
        assertFalse(client.recordExposure(client.evaluate("another", "checkout", attributes)))
        assertEquals(2L, client.exposureDedupRejectedCount)
        assertNotNull(client.assign("u", "checkout", attributes).variant)
        assertEquals(2L, client.exposureDedupRejectedCount)
        assertEquals(1, client.exposureDedupCount)
        client.close()
        assertEquals(SdkResponseCode.CLIENT_ERROR.code, client.assign("u", "checkout", attributes).resultCode)
        assertEquals(0, client.exposureDedupCount)
        assertEquals(2L, client.exposureDedupRejectedCount)
    }

    @Test
    fun `queue saturation and unavailable configuration are not dedup capacity failures`() {
        create(PrismClientOptions(eventBatchSize = 1, eventQueueCapacity = 1, exposureDedupCapacity = 2,
            configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1)))
        eventGate = CountDownLatch(1)
        val attributes = mapOf("age" to 25, "country" to "KR")
        assertNotNull(client.assign("u", "checkout", attributes).variant)
        assertEquals(SdkResponseCode.CLIENT_ERROR.code, client.assign("new", "checkout", attributes).resultCode)
        assertEquals(0L, client.exposureDedupRejectedCount)
        assertEquals(1, client.exposureDedupCount)
        eventGate!!.countDown()
        client.close()
        failConfig.set(true)
        create(PrismClientOptions(initializationTimeout = Duration.ZERO,
            configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1)))
        assertFalse(client.refreshConfig())
        assertEquals(SdkResponseCode.CLIENT_ERROR.code, client.assign("u", "checkout", attributes).resultCode)
        assertEquals(0L, client.exposureDedupRejectedCount)
        assertEquals(0, client.exposureDedupCount)
    }

    @Test
    fun `population and experiment dedup usage and rejection counters are independent`() {
        config.set(configuration().copy(holdout = HoldoutConfig("permanent", 0, true)))
        create(PrismClientOptions(exposureDedupCapacity = 1, configSyncInterval = Duration.ofHours(1),
            eventFlushInterval = Duration.ofHours(1)))
        assertTrue(client.refreshConfig())
        assertEquals(0, client.populationExposureDedupCount)
        assertTrue(client.recordPopulationExposure("u"))
        assertNotNull(client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        assertTrue(client.flush())
        assertEquals(1, client.populationExposureDedupCount)
        assertEquals(1, client.exposureDedupCount)
        assertTrue(client.recordPopulationExposure("u"))
        repeat(3) { assertFalse(client.recordPopulationExposure("new")) }
        assertEquals(3L, client.populationExposureDedupRejectedCount)
        assertEquals(0L, client.exposureDedupRejectedCount)
        assertTrue(client.trackPopulationConversion("u", "purchase"))
        assertTrue(client.flush())
        client.close()
        assertEquals(0, client.populationExposureDedupCount)
        assertEquals(3L, client.populationExposureDedupRejectedCount)
    }

    @Test
    fun `permanent rejection releases dedup usage and preserves capacity rejection totals`() {
        create(PrismClientOptions(exposureDedupCapacity = 1, configSyncInterval = Duration.ofHours(1),
            eventFlushInterval = Duration.ofHours(1)))
        val attributes = mapOf("age" to 25, "country" to "KR")
        val first = client.assign("u", "checkout", attributes)
        client.assign("new", "checkout", attributes)
        rejectExposure.set(true)
        assertFalse(client.flush())
        assertEquals(0, client.exposureDedupCount)
        assertEquals(1L, client.exposureDedupRejectedCount)
        rejectExposure.set(false)
        assertNotEquals(first.exposureEventId, client.assign("u", "checkout", attributes).exposureEventId)
        assertEquals(1, client.exposureDedupCount)
    }

    @Test
    fun `remote mode exposes zero local dedup diagnostics`() {
        create(PrismClientOptions(evaluationMode = EvaluationMode.REMOTE))
        assertEquals(0, client.exposureDedupCount)
        assertEquals(0, client.populationExposureDedupCount)
        assertEquals(0L, client.exposureDedupRejectedCount)
        assertEquals(0L, client.populationExposureDedupRejectedCount)
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
    fun `normal flush uses its own budget even with a short shutdown timeout`() {
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            flushTimeout = Duration.ofMillis(900), shutdownTimeout = Duration.ofMillis(50)))
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        eventGate = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val flushing = pool.submit<Boolean> { client.flush() }
            assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { flushing.get(150, TimeUnit.MILLISECONDS) }
            eventGate!!.countDown()
            assertTrue(flushing.get(2, TimeUnit.SECONDS))
            assertEquals(0, client.pendingEventCount)
        } finally { eventGate!!.countDown(); pool.shutdownNow() }
    }

    @Test
    fun `close can deliver an event retained after the shorter normal flush budget expires`() {
        create(PrismClientOptions(configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            flushTimeout = Duration.ofMillis(50), shutdownTimeout = Duration.ofMillis(900)))
        client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR"))
        eventGate = CountDownLatch(1)
        assertFalse(client.flush())
        assertEquals(1, client.pendingEventCount)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val closing = pool.submit { client.close() }
            assertTrue(eventArrived.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { closing.get(150, TimeUnit.MILLISECONDS) }
            eventGate!!.countDown()
            closing.get(2, TimeUnit.SECONDS)
            assertEquals(0, client.pendingEventCount)
        } finally { eventGate!!.countDown(); pool.shutdownNow() }
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
