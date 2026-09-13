package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.dto.config.*
import io.github.silbaram.prism.common.rest.dto.event.*
import okhttp3.mockwebserver.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Duration
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.*

class ExposureLookupTest {
    private val mapper = jacksonObjectMapper()
    private val server = MockWebServer()
    private val lookupCount = AtomicInteger()
    private val lookupArrived = CountDownLatch(1)
    private val lookup = AtomicReference(exposure("A"))
    private val deliveries = CopyOnWriteArrayList<ClientEvent>()
    @Volatile private var lookupGate: CountDownLatch? = null
    private lateinit var client: PrismClient

    private fun exposure(variant: String) = AssignmentResponse("u", "checkout", variant, "0000", "Success",
        "a".repeat(64), UUID.randomUUID().toString())

    @BeforeEach fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v1/config" -> MockResponse().setBody(mapper.writeValueAsString(ConfigResponse("a".repeat(64),
                    listOf(ExperimentConfig("checkout", "ACTIVE", listOf(VariantConfig("A", 100)))))))
                "/v1/assignments" -> {
                    assertEquals("OCCURRED_AT", request.requestUrl!!.queryParameter("order"))
                    lookupCount.incrementAndGet()
                    val response = lookup.get().copy(userId = request.requestUrl!!.queryParameter("userId")!!)
                    lookupArrived.countDown()
                    lookupGate?.await(5, TimeUnit.SECONDS)
                    MockResponse().setBody(mapper.writeValueAsString(response))
                }
                "/v1/events" -> {
                    val events = mapper.readValue<EventsRequest>(request.body.readUtf8()).events
                    deliveries.addAll(events)
                    MockResponse().setBody(mapper.writeValueAsString(EventsResponse(events.map {
                        EventResult(it.eventId, EventStatus.ACCEPTED)
                    })))
                }
                else -> MockResponse().setResponseCode(404)
            }
        }
        server.start()
        client = PrismClient(server.url("/").toString(), Duration.ofSeconds(3), PrismClientOptions(
            configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1),
            exposureCacheMaximumSize = 1))
    }

    @AfterEach fun stop() {
        lookupGate?.countDown()
        client.close()
        server.shutdown()
    }

    @Test fun `lookup TTL refreshes a cross instance exposure before attributing another conversion`() {
        val wrapper = PrismExperimentClient(client, Duration.ofMillis(20))
        assertTrue(wrapper.trackIfAssigned("u", "checkout", "purchase"))
        assertTrue(client.flush())
        val oldLookups = lookupCount.get()
        val newer = exposure("B")
        lookup.set(newer)
        Thread.sleep(60)
        assertTrue(wrapper.trackIfAssigned("u", "checkout", "purchase"))
        assertTrue(client.flush())
        assertTrue(lookupCount.get() > oldLookups)
        assertEquals(newer.exposureEventId, deliveries.last().exposureEventId)
        assertEquals(listOf("A", "B"), deliveries.map { it.variant })
    }

    @Test fun `zero TTL can track looked up exposures without losing their reference`() {
        repeat(2) { assertTrue(client.trackConversion("u", "checkout", "purchase", Duration.ZERO)) }
        assertEquals(2, lookupCount.get())
        assertTrue(client.flush())
        assertTrue(deliveries.all { it.exposureEventId == lookup.get().exposureEventId })
    }

    @Test fun `fresh lookup can be tracked even when a hot cache entry prevents its admission`() {
        repeat(100) { index ->
            repeat(5) { assertNotNull(client.getAssignment("hot", "checkout").variant) }
            assertTrue(client.trackConversion("cold-$index", "checkout", "purchase"), "lookup $index")
        }
        assertTrue(client.flush())
        assertEquals(100, deliveries.size)
    }

    @Test fun `pending local exposures survive TTL and cache eviction until acknowledged`() {
        assertEquals("A", client.assign("u", "checkout").variant)
        repeat(10) { assertEquals("A", client.assign("other-$it", "checkout").variant) }
        lookup.set(exposure("B"))
        assertTrue(client.trackConversion("u", "checkout", "purchase", Duration.ZERO))
        assertEquals(0, lookupCount.get())
        assertTrue(client.flush())
        val local = deliveries.first { it.userId == "u" && it.type == "exposure" }
        assertEquals(local.eventId, deliveries.last().exposureEventId)
        assertTrue(client.trackConversion("u", "checkout", "purchase", Duration.ZERO))
        assertTrue(client.flush())
        assertEquals(lookup.get().exposureEventId, deliveries.last().exposureEventId)
    }

    @Test fun `a slow lookup does not block local assign or overwrite its newer exposure`() {
        assertEquals("A", client.evaluate("u", "checkout").variant)
        lookup.set(exposure("B"))
        lookupGate = CountDownLatch(1)
        val wrapper = PrismExperimentClient(client)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val tracking = workers.submit<Boolean> { wrapper.trackIfAssigned("u", "checkout", "purchase") }
            assertTrue(lookupArrived.await(2, TimeUnit.SECONDS))
            val assignment = workers.submit<AssignmentOutcome> { wrapper.assign("u", "checkout") }
            assertEquals("A", assignment.get(1, TimeUnit.SECONDS).variant)
            assertEquals(1L, lookupGate!!.count)
            lookupGate!!.countDown()
            assertTrue(tracking.get(2, TimeUnit.SECONDS))
            assertTrue(client.flush())
            assertEquals(listOf("A", "A"), deliveries.map { it.variant })
            assertEquals(deliveries.first().eventId, deliveries.last().exposureEventId)
        } finally { lookupGate!!.countDown(); workers.shutdownNow() }
    }
}
