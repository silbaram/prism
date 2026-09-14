package io.github.silbaram.prism.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.dto.config.ConfigResponse
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import io.github.silbaram.prism.sdk.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.api.keys=prism-test-api-key-0123456789abcdef,prism-rotated-api-key-0123456789abcdef",
    "spring.datasource.url=jdbc:h2:mem:local-evaluation;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
    "spring.jpa.properties.hibernate.show_sql=false", "prism.config.cache-ttl=PT0.000000001S"
])
class LocalEvaluationIntegrationTest {
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var receipts: EventReceiptRepository
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var httpMapper: tools.jackson.databind.json.JsonMapper
    private val mapper = jacksonObjectMapper()
    private lateinit var http: HttpClient
    private val baseUrl get() = "http://localhost:${environment.getProperty("local.server.port")}"

    @BeforeEach
    fun setup() {
        http = HttpClient.newHttpClient()
        conversions.deleteAll(); impressions.deleteAll(); receipts.deleteAll(); experiments.deleteAll()
        experiments.save(ExperimentEntity(key = "checkout", description = "", goalEventName = "purchase", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "Z", weight = 30))
            addVariant(VariantEntity(name = "A", weight = 70))
        })
    }

    @AfterEach fun closeHttp() { http.shutdownNow() }

    private fun getConfig(etag: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/config")).header("X-Prism-Api-Key", "prism-test-api-key-0123456789abcdef").GET()
        etag?.let { request.header("If-None-Match", it) }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun post(events: List<ClientEvent>): EventsResponse {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl/v1/events")).header("X-Prism-Api-Key", "prism-test-api-key-0123456789abcdef")
            .header("Content-Type", "application/json").timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(EventsRequest(events)))).build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return mapper.readValue(response.body())
    }

    private fun exposure(user: String = "u", variant: String = "A", id: String = UUID.randomUUID().toString()) =
        ClientEvent(id, "exposure", user, "checkout", variant, Instant.now().toString(), "a".repeat(64))
    private fun conversion(exposure: ClientEvent) = ClientEvent(UUID.randomUUID().toString(), "conversion",
        exposure.userId, exposure.experimentKey, exposure.variant, Instant.now().toString(), exposure.configVersion,
        "purchase", exposure.eventId)

    @Test fun `future JSON fields retain their values and participate in idempotency hashes`() {
        val event = exposure("future").copy(extensions = linkedMapOf("future_meta" to linkedMapOf(
            "amount" to java.math.BigDecimal("0.12345678901234567890123456789"), "trace" to listOf("abc", null, true)),
            "extensions" to mapOf("nested" to "preserved")))
        val decoded = httpMapper.readValue(mapper.writeValueAsString(EventsRequest(listOf(event))), EventsRequest::class.java).events.single()
        assertEquals(mapper.writeValueAsString(event), mapper.writeValueAsString(decoded))
        val pipelineMapper = jacksonObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        val roundTrip = pipelineMapper.readValue<ClientEvent>(pipelineMapper.writeValueAsBytes(decoded))
        assertEquals(mapper.writeValueAsString(event), pipelineMapper.writeValueAsString(roundTrip))
        org.mockito.Mockito.clearInvocations(experiments)
        assertEquals(EventStatus.ACCEPTED, post(listOf(event)).results.single().status)
        org.mockito.Mockito.verify(experiments, org.mockito.Mockito.times(1)).findByKey(event.experimentKey)
        val expectedHash = java.security.MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(event))
            .joinToString("") { "%02x".format(it) }
        assertEquals(expectedHash, receipts.findById(event.eventId).orElseThrow().payloadHash)
        assertEquals(EventStatus.DUPLICATE, post(listOf(event)).results.single().status)
        assertEquals(EventStatus.REJECTED, post(listOf(event.copy(extensions = mapOf("future_meta" to "changed")))).results.single().status)
    }

    @Test fun `future fields survive arbitrary field order and repeated decoding across mapper versions`() {
        val pipelineMapper = jacksonObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        val event = exposure("round-trip").copy(extensions = linkedMapOf(
            "extensionFields" to mapOf("nullable" to null, "array" to listOf(1, true, "s")),
            "extensions" to "literal extension name",
            "future" to mapOf("tiny" to java.math.BigDecimal("1e-400"),
                "large" to java.math.BigInteger("1234567890123456789012345678901234567890"))))
        val expected = pipelineMapper.writeValueAsString(event)
        val fields = pipelineMapper.readValue<LinkedHashMap<String, Any?>>(expected).entries.toList()
        listOf(fields, fields.reversed(), fields.drop(7) + fields.take(7)).forEach { ordered ->
            var json = pipelineMapper.writeValueAsString(ordered.associate { it.key to it.value })
            repeat(3) {
                val httpEvent = httpMapper.readValue(json, ClientEvent::class.java)
                json = pipelineMapper.writeValueAsString(pipelineMapper.readValue<ClientEvent>(pipelineMapper.writeValueAsBytes(httpEvent)))
                assertEquals(expected, json)
            }
        }
    }

    @Test fun `extension limits reject only the invalid events in a batch`() {
        val large = exposure("large").copy(extensions = mapOf("future" to "x".repeat(16385)))
        val many = exposure("many").copy(extensions = (1..17).associate { "field$it" to it })
        val valid = exposure("valid-extension").copy(extensions = mapOf("future" to null))
        assertEquals(listOf(EventStatus.REJECTED, EventStatus.REJECTED, EventStatus.ACCEPTED),
            post(listOf(large, many, valid)).results.map { it.status })
        assertEquals(1, impressions.count())
        assertEquals(1, receipts.count())
    }

    @Test fun `HTTP batch rejects an unrepresentable baseline instant and still accepts its valid event`() {
        val invalid = exposure("invalid-time").copy(analysis = ExposureAnalysisContext(baselineValue = 1.0,
            baselineMeasuredAt = "+1000000000-01-01T00:00:00Z"))
        val valid = exposure("valid-time")
        assertEquals(listOf(EventStatus.REJECTED, EventStatus.ACCEPTED), post(listOf(invalid, valid)).results.map { it.status })
        assertNull(impressions.findByEventId(invalid.eventId))
        assertNotNull(impressions.findByEventId(valid.eventId))
    }

    @Test
    fun `all SDK routes require a valid header key and support overlapping rotation keys`() {
        listOf("/v1/config", "/v1/assign?userId=u&experimentKey=checkout", "/v1/assignments?userId=u&experimentKey=checkout",
            "/v1/events", "/v1/conversions").forEach { path ->
            val request = HttpRequest.newBuilder(URI.create("$baseUrl$path"))
            if (path in listOf("/v1/events", "/v1/conversions")) request.POST(HttpRequest.BodyPublishers.ofString("{}"))
            assertEquals(401, http.send(request.build(), HttpResponse.BodyHandlers.ofString()).statusCode(), path)
        }
        val wrong = HttpRequest.newBuilder(URI.create("$baseUrl/v1/config")).header("X-Prism-Api-Key", "wrong").GET().build()
        assertEquals(401, http.send(wrong, HttpResponse.BodyHandlers.ofString()).statusCode())
        val rotated = HttpRequest.newBuilder(URI.create("$baseUrl/v1/config"))
            .header("X-Prism-Api-Key", "prism-rotated-api-key-0123456789abcdef").GET().build()
        assertEquals(200, http.send(rotated, HttpResponse.BodyHandlers.ofString()).statusCode())
        val duplicate = HttpRequest.newBuilder(URI.create("$baseUrl/v1/config"))
            .header("X-Prism-Api-Key", "prism-test-api-key-0123456789abcdef")
            .header("X-Prism-Api-Key", "prism-rotated-api-key-0123456789abcdef").GET().build()
        assertEquals(401, http.send(duplicate, HttpResponse.BodyHandlers.ofString()).statusCode())
        assertEquals(401, http.send(HttpRequest.newBuilder(URI.create("$baseUrl/v1/config")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode())
        assertEquals(0, impressions.count())
        assertEquals(0, conversions.count())
    }

    @Test
    fun `periods and participation propagate to local and remote assignment without recording nonparticipants`() {
        val experiment = experiments.findByKey("checkout")!!
        experiment.trafficAllocation = 0
        experiments.save(experiment)
        val configuration = mapper.readValue<ConfigResponse>(getConfig().body())
        assertEquals(0, configuration.experiments.single().trafficAllocation)
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { local ->
            PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef", evaluationMode = EvaluationMode.REMOTE)).use { remote ->
                assertNull(local.assign("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
                assertNull(remote.assign("u", "checkout").variant)
            }
        }
        experiment.trafficAllocation = 100
        experiment.endsAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)
        experiments.save(experiment)
        assertNotNull(mapper.readValue<ConfigResponse>(getConfig().body()).experiments.single().endsAt)
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { client ->
            assertNull(client.assign("u", "checkout", mapOf("age" to 25, "country" to "KR")).variant)
        }
        assertEquals(0, impressions.count())
    }

    @Test
    fun `config exports only active experiments with stable variant order and content etags`() {
        ExperimentStatus.entries.filter { it != ExperimentStatus.ACTIVE }.forEach { status ->
            experiments.save(ExperimentEntity(key = status.name, description = "", status = status).apply {
                addVariant(VariantEntity(name = "B", weight = 100))
            })
        }
        val response = getConfig()
        assertEquals(200, response.statusCode())
        val config = mapper.readValue<ConfigResponse>(response.body())
        assertEquals(listOf("checkout"), config.experiments.map { it.key })
        assertEquals(listOf("Z", "A"), config.experiments.single().variants.map { it.name })
        assertEquals("purchase", config.experiments.single().goalEventName)
        val etag = response.headers().firstValue("ETag").orElseThrow()
        assertEquals("\"${config.version}\"", etag)
        assertEquals(304, getConfig(etag).statusCode())
        assertEquals(304, getConfig("W/$etag").statusCode())
        val entity = experiments.findByKey("checkout")!!
        entity.status = ExperimentStatus.ENDED
        experiments.save(entity)
        val changed = getConfig(etag)
        assertEquals(200, changed.statusCode())
        assertTrue(mapper.readValue<ConfigResponse>(changed.body()).experiments.isEmpty())
        assertNotEquals(etag, changed.headers().firstValue("ETag").orElseThrow())
    }

    @Test
    fun `SDK locally evaluates targeting and batch events appear in existing metric queries`() {
        experiments.save(ExperimentEntity(key = "targeted", description = "", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "T", weight = 100))
            addTargetingRule(TargetingRuleEntity(expression = "age >= 20 && country == 'KR'"))
        })
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef", configSyncInterval = Duration.ofHours(1),
            eventFlushInterval = Duration.ofHours(1))).use { local ->
            assertNull(local.evaluate("young", "targeted", mapOf("age" to 10, "country" to "KR")).variant)
            val target = local.evaluate("adult", "targeted", mapOf("age" to 25, "country" to "KR"))
            assertEquals("T", target.variant)
            assertEquals(0L, impressions.count())
            assertFalse(local.trackConversion("adult", "targeted", "purchase"))
            assertTrue(local.recordExposure(target))
            assertTrue(local.trackConversion("adult", "targeted", "purchase"))
            val assignment = local.assign("buyer", "checkout")
            assertTrue(local.trackConversion("buyer", "checkout", "purchase"))
            assertEquals(0L, impressions.count())
            assertEquals(0L, conversions.count())
            assertTrue(local.flush())
            assertEquals(2L, impressions.count())
            assertEquals(2L, conversions.count())
            val stats = conversions.countConversionsByVariant("checkout", "purchase").single()
            assertEquals(assignment.variant, stats[0])
            assertEquals(1L, stats[1])
            assertTrue(local.flush())
            assertEquals(4L, receipts.count())
        }
    }

    @Test
    fun `a different SDK instance looks up the committed exposure without assigning again`() {
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { first ->
            first.assign("cross-instance", "checkout")
            assertTrue(first.flush())
        }
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { second ->
            assertFalse(second.trackConversion("unexposed", "checkout", "purchase"))
            assertTrue(PrismExperimentClient(second).trackIfAssigned("cross-instance", "checkout", "purchase"))
            assertTrue(second.flush())
        }
        assertEquals(1L, impressions.count())
        assertEquals(1L, conversions.count())
        assertEquals(impressions.findAll().single().id, conversions.findAll().single().impressionId)
    }

    @Test
    fun `local and legacy remote assignment select identical variants`() {
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { local ->
            PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef", evaluationMode = EvaluationMode.REMOTE)).use { remote ->
                repeat(30) { index ->
                    assertEquals(remote.assign("u-$index", "checkout").variant, local.evaluate("u-$index", "checkout").variant)
                }
            }
            assertEquals(30L, impressions.count())
            assertEquals(0, local.pendingEventCount)
        }
    }

    @Test
    fun `out of order batch and retries produce one exposure and conversion`() {
        val exposure = exposure()
        val conversion = conversion(exposure)
        assertEquals(listOf(EventStatus.ACCEPTED, EventStatus.ACCEPTED), post(listOf(conversion, exposure)).results.map { it.status })
        assertEquals(listOf(EventStatus.DUPLICATE, EventStatus.DUPLICATE), post(listOf(conversion, exposure)).results.map { it.status })
        assertEquals(1L, impressions.count())
        assertEquals(1L, conversions.count())
        assertEquals(impressions.findAll().single().id, conversions.findAll().single().impressionId)
        assertEquals(2L, receipts.count())
        assertEquals(EventStatus.REJECTED, post(listOf(exposure.copy(userId = "another"))).results.single().status)
        assertEquals(1L, impressions.count())
    }

    @Test
    fun `conversion arriving first retries without manufacturing exposure and rejects wrong attribution`() {
        val exposure = exposure()
        val conversion = conversion(exposure)
        assertEquals(EventStatus.RETRY, post(listOf(conversion)).results.single().status)
        assertEquals(0L, impressions.count())
        assertEquals(0L, receipts.count())
        post(listOf(exposure))
        assertEquals(EventStatus.REJECTED, post(listOf(conversion.copy(userId = "U"))).results.single().status)
        assertEquals(EventStatus.REJECTED, post(listOf(conversion.copy(variant = "Z"))).results.single().status)
        assertEquals(EventStatus.REJECTED, post(listOf(conversion.copy(configVersion = "b".repeat(64)))).results.single().status)
        assertEquals(EventStatus.ACCEPTED, post(listOf(conversion)).results.single().status)
    }

    @Test
    fun `concurrent duplicate delivery commits a single global receipt and log`() {
        val exposure = exposure()
        val pool = Executors.newFixedThreadPool(6)
        val ready = CountDownLatch(6)
        try {
            val futures = (1..6).map { pool.submit<EventStatus> {
                ready.countDown(); check(ready.await(5, TimeUnit.SECONDS))
                post(listOf(exposure)).results.single().status
            } }
            val statuses = futures.map { it.get(15, TimeUnit.SECONDS) }
            assertEquals(1, statuses.count { it == EventStatus.ACCEPTED })
            assertEquals(5, statuses.count { it == EventStatus.DUPLICATE })
            assertEquals(1L, impressions.count())
            assertEquals(1L, receipts.count())
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `invalid historical experiments are quarantined while healthy config updates continue`() {
        val invalid = experiments.save(ExperimentEntity(key = "invalid", description = "", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "B", weight = 50))
            addVariant(VariantEntity(name = "B", weight = 50))
        })
        val response = getConfig()
        assertEquals(listOf("checkout"), mapper.readValue<ConfigResponse>(response.body()).experiments.map { it.key })
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { client ->
            assertNotNull(client.evaluate("u", "checkout").variant)
            assertNull(client.evaluate("u", "invalid").variant)
            val healthy = experiments.findByKey("checkout")!!
            healthy.status = ExperimentStatus.PAUSED
            experiments.save(healthy)
            assertTrue(client.refreshConfig())
            assertNull(client.evaluate("u", "checkout").variant)
            invalid.variants[1].name = "C"
            experiments.save(invalid)
            assertTrue(client.refreshConfig())
            assertNotNull(client.evaluate("u", "invalid").variant)
        }
    }

    @Test
    fun `cross instance lookup uses occurrence time and deterministic ties despite delayed batches`() {
        val older = exposure(variant = "A").copy(timestamp = "2026-09-13T00:00:00.100000Z")
        val newer = exposure(variant = "Z").copy(timestamp = "2026-09-13T00:00:00.900000Z")
        assertEquals(EventStatus.ACCEPTED, post(listOf(newer)).results.single().status)
        assertEquals(EventStatus.ACCEPTED, post(listOf(older)).results.single().status)
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { client ->
            assertTrue(client.trackConversion("u", "checkout", "purchase"))
            assertTrue(client.flush())
            assertEquals(newer.eventId, impressions.findById(conversions.findAll().single().impressionId!!).orElseThrow().eventId)
        }
        // Legacy lookup intentionally retains its recorded-order contract.
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef", evaluationMode = EvaluationMode.REMOTE)).use { client ->
            assertEquals("A", client.getAssignment("u", "checkout").variant)
        }
        val tieHigh = exposure(user = "tie", variant = "Z", id = "ffffffff-ffff-ffff-ffff-ffffffffffff")
            .copy(timestamp = "2026-09-13T00:00:01.123456Z")
        val tieLow = tieHigh.copy(eventId = "00000000-0000-0000-0000-000000000001", variant = "A")
        post(listOf(tieHigh, tieLow))
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef")).use { client ->
            assertEquals(tieHigh.eventId, client.getAssignment("tie", "checkout").exposureEventId)
        }
    }

    @Test
    fun `explicit SDK exposure reference crosses instances before the exposure is delivered`() {
        PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef", eventFlushInterval = Duration.ofHours(1))).use { source ->
            PrismClient(baseUrl, options = PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef", eventFlushInterval = Duration.ofHours(1))).use { destination ->
                val assignment = source.assign("handoff", "checkout")
                assertNotNull(assignment.exposureEventId)
                assertTrue(destination.trackConversion(assignment, "purchase"))
                assertFalse(destination.flush())
                assertEquals(1, destination.pendingEventCount)
                assertEquals(0L, receipts.count())
                assertTrue(source.flush())
                assertTrue(destination.flush())
                assertEquals(assignment.exposureEventId, impressions.findAll().single().eventId)
                assertEquals(impressions.findAll().single().id, conversions.findAll().single().impressionId)
                assertEquals(2L, receipts.count())
            }
        }
    }

    @Test
    fun `a conversion cannot permanently retry a reference that is already a conversion`() {
        val exposure = exposure()
        val conversion = conversion(exposure)
        assertTrue(post(listOf(exposure, conversion)).results.all { it.status == EventStatus.ACCEPTED })
        val invalid = conversion.copy(eventId = UUID.randomUUID().toString(), exposureEventId = conversion.eventId)
        assertEquals(EventStatus.REJECTED, post(listOf(invalid)).results.single().status)
        assertEquals(2L, receipts.count())
    }

    @Test
    fun `invalid events are rejected individually without suppressing valid siblings`() {
        val good = exposure()
        val bad = exposure().copy(timestamp = "not-a-time")
        val unknown = exposure().copy(experimentKey = "unknown")
        assertEquals(listOf(EventStatus.REJECTED, EventStatus.ACCEPTED, EventStatus.REJECTED),
            post(listOf(bad, good, unknown)).results.map { it.status })
        assertEquals(1L, impressions.count())
        assertEquals(1L, receipts.count())
    }
}
