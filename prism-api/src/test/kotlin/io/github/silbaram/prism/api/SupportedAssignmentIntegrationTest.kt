package io.github.silbaram.prism.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.api.traffic.application.service.StickyAssignmentService
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import io.github.silbaram.prism.sdk.*
import io.github.silbaram.prism.starter.annotation.PrismStrategy
import io.github.silbaram.prism.starter.strategy.PrismStrategyResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.support.StaticApplicationContext
import org.springframework.core.env.Environment
import java.net.URI
import java.net.URLEncoder
import java.net.http.*
import java.nio.charset.StandardCharsets
import java.time.Duration

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.api.keys=prism-test-api-key-0123456789abcdef",
    "spring.datasource.url=jdbc:h2:mem:supported-assignment;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
    "spring.jpa.properties.hibernate.show_sql=false", "prism.config.cache-ttl=PT0.000000001S"
])
class SupportedAssignmentIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var receipts: EventReceiptRepository
    @Autowired lateinit var stickyAssignments: StickyAssignmentRepository
    @Autowired lateinit var sticky: StickyAssignmentService
    @Autowired lateinit var environment: Environment
    private val baseUrl get() = "http://localhost:${environment.getProperty("local.server.port")}"
    private val mapper = jacksonObjectMapper()

    @BeforeEach
    fun clear() {
        conversions.deleteAll()
        impressions.deleteAll()
        receipts.deleteAll()
        stickyAssignments.deleteAll()
        experiments.deleteAll()
    }

    private fun experiment(controlWeight: Int = 50, stickyBucketing: Boolean = false) =
        experiments.save(ExperimentEntity(key = "checkout", description = "", goalEventName = "purchase",
            status = ExperimentStatus.ACTIVE, stickyBucketing = stickyBucketing).apply {
            addVariant(VariantEntity(name = "control", weight = controlWeight))
            addVariant(VariantEntity(name = "B", weight = 100 - controlWeight))
        })

    private fun client(mode: EvaluationMode, store: StickyAssignmentStore = InMemoryStickyAssignmentStore()) =
        PrismClient(baseUrl, options = PrismClientOptions(evaluationMode = mode,
            apiKey = "prism-test-api-key-0123456789abcdef", stickyAssignmentStore = store,
            configSyncInterval = Duration.ofHours(1), eventFlushInterval = Duration.ofHours(1)))

    private fun context(vararg strategies: Checkout) = StaticApplicationContext().apply {
        strategies.forEachIndexed { index, strategy -> beanFactory.registerSingleton("strategy-$index", strategy) }
    }

    @ParameterizedTest @EnumSource(EvaluationMode::class)
    fun `incomplete strategy registry falls back without recording either arm or conversion`(mode: EvaluationMode) {
        experiment(stickyBucketing = true)
        client(mode).use { transport ->
            context(Control()).use { context ->
                val client = PrismExperimentClient(transport)
                val resolver = PrismStrategyResolver(context, client)
                // Block every arm, including users whose proposed arm is implemented.
                repeat(12) { user ->
                    assertEquals("control", resolver.resolve(Checkout::class.java, "user-$user", "checkout").name())
                    assertFalse(client.trackIfAssigned("user-$user", "checkout", "purchase"))
                }
                assertNull(transport.assign("plain-sdk-user", "checkout").variant)
                assertFalse(client.assign("plain-wrapper-user", "checkout").assigned)
                assertTrue(transport.flush())
            }
        }
        assertEquals(0L, impressions.count())
        assertEquals(0L, conversions.count())
        assertEquals(0L, stickyAssignments.count())
    }

    @ParameterizedTest @EnumSource(EvaluationMode::class)
    fun `fallback cannot convert a prior legitimate exposure from the same client`(mode: EvaluationMode) {
        experiment()
        client(mode).use { transport ->
            val previous = transport.assign("returning-user", "checkout")
            assertNotNull(previous.variant)
            assertTrue(transport.flush())
            context(Control()).use { context ->
                val client = PrismExperimentClient(transport)
                val resolver = PrismStrategyResolver(context, client)
                assertEquals("control", resolver.resolve(Checkout::class.java, "returning-user", "checkout").name())
                assertFalse(client.trackIfAssigned("returning-user", "checkout", "purchase"))
                assertFalse(transport.trackConversion(previous, "purchase"))
                assertTrue(transport.flush())
            }
        }
        assertEquals(1L, impressions.count())
        assertEquals(0L, conversions.count())
    }

    @ParameterizedTest @EnumSource(EvaluationMode::class)
    fun `complete registry executes and converts the actual assigned arm`(mode: EvaluationMode) {
        experiment()
        client(mode).use { transport ->
            context(Control(), Treatment()).use { context ->
                val client = PrismExperimentClient(transport)
                val selected = PrismStrategyResolver(context, client).resolve(Checkout::class.java, "valid-user", "checkout")
                assertTrue(client.trackIfAssigned("valid-user", "checkout", "purchase"))
                assertTrue(transport.flush())
                assertEquals(selected.name(), impressions.findAll().single().variant)
                assertEquals(selected.name(), conversions.findAll().single().variant)
            }
        }
        assertEquals(1L, impressions.count())
        assertEquals(1L, conversions.count())
    }

    @ParameterizedTest @EnumSource(EvaluationMode::class)
    fun `zero weight arm does not require a strategy but an unsupported sticky arm is rejected`(mode: EvaluationMode) {
        experiment(controlWeight = 100, stickyBucketing = true)
        if (mode == EvaluationMode.REMOTE) sticky.choose("checkout", "stale-user", "B")
        val store = InMemoryStickyAssignmentStore().apply { getOrPut("stale-user", "checkout", "B") }
        client(mode, store).use { transport ->
            val historical = transport.assign("stale-user", "checkout")
            assertEquals("B", historical.variant)
            assertTrue(transport.flush())
            val valid = transport.assignSupported("valid-user", "checkout", setOf("control"))
            assertEquals("control", valid.variant)
            assertTrue(transport.trackConversion(valid, "purchase"))
            assertNull(transport.assignSupported("stale-user", "checkout", setOf("control")).variant)
            assertFalse(transport.trackConversion(historical, "purchase"))
            assertFalse(transport.trackConversion("stale-user", "checkout", "purchase"))
            assertTrue(transport.flush())
        }
        assertEquals(setOf("stale-user", "valid-user"), impressions.findAll().map { it.userId }.toSet())
        assertEquals(2L, impressions.count())
        assertEquals(listOf("valid-user"), conversions.findAll().map { it.userId })
    }

    @ParameterizedTest @EnumSource(EvaluationMode::class)
    fun `valid delayed conversion remains allowed after experiment pauses`(mode: EvaluationMode) {
        experiment()
        client(mode).use { transport ->
            val assignment = transport.assignSupported("late-user", "checkout", setOf("control", "B"))
            assertNotNull(assignment.variant)
            assertTrue(transport.flush())
            experiments.save(experiments.findByKey("checkout")!!.apply { status = ExperimentStatus.PAUSED })
            if (mode == EvaluationMode.LOCAL) assertTrue(transport.refreshConfig())
            assertTrue(transport.trackConversion(assignment, "purchase"))
            assertTrue(transport.flush())
        }
        assertEquals(1L, impressions.count())
        assertEquals(1L, conversions.count())
    }

    @ParameterizedTest @EnumSource(EvaluationMode::class)
    fun `missing and duplicate strategies fail before any assignment writes`(mode: EvaluationMode) {
        experiment()
        client(mode).use { transport ->
            for (beans in listOf(emptyArray<Checkout>(), arrayOf<Checkout>(Control(), DuplicateControl()))) {
                context(*beans).use { context ->
                    assertThrows<RuntimeException> {
                        PrismStrategyResolver(context, PrismExperimentClient(transport)).resolve(Checkout::class.java, "u", "checkout")
                    }
                }
            }
            assertTrue(transport.flush())
        }
        assertEquals(0L, impressions.count())
        assertEquals(0L, conversions.count())
        assertEquals(0L, stickyAssignments.count())
    }

    @Test
    fun `assignment HTTP rejects blank and oversized identities before sticky or exposure writes`() {
        experiment(stickyBucketing = true)
        HttpClient.newHttpClient().use { http ->
            for ((user, key) in listOf("" to "checkout", " \t " to "checkout", "u".repeat(256) to "checkout",
                "u" to "", "u" to " \t ", "u" to "k".repeat(256))) {
                val encode = { value: String -> URLEncoder.encode(value, StandardCharsets.UTF_8) }
                val get = request("/v1/assign?userId=${encode(user)}&experimentKey=${encode(key)}").GET().build()
                assertEquals(400, http.send(get, HttpResponse.BodyHandlers.ofString()).statusCode(), "$user / $key")
                val body = mapOf("userId" to user, "experimentKey" to key, "supportedVariants" to listOf("control", "B"))
                assertEquals(400, http.send(post("/v1/assign/supported", body), HttpResponse.BodyHandlers.ofString()).statusCode())
            }
            for (variants in listOf(emptyList(), listOf(" "), listOf("v".repeat(256)), (1..1001).map { "v$it" })) {
                val body = mapOf("userId" to "u", "experimentKey" to "checkout", "supportedVariants" to variants)
                assertEquals(400, http.send(post("/v1/assign/supported", body), HttpResponse.BodyHandlers.ofString()).statusCode())
                assertEquals(400, http.send(post("/v1/conversions/supported", body + ("eventName" to "purchase")),
                    HttpResponse.BodyHandlers.ofString()).statusCode())
            }
        }
        assertEquals(0L, impressions.count())
        assertEquals(0L, conversions.count())
        assertEquals(0L, stickyAssignments.count())
    }

    @Test
    fun `remote assignment preserves case Unicode and spaces in valid 255 character identities`() {
        experiment()
        val users = listOf("user", "USER", " " + "가".repeat(253) + " ")
        client(EvaluationMode.REMOTE).use { client ->
            for (user in users) {
                assertNotNull(client.assign(user, "checkout").variant)
                assertTrue(client.trackConversion(user, "checkout", "purchase"))
            }
        }
        assertEquals(users.toSet(), impressions.findAll().map { it.userId }.toSet())
        assertEquals(users.toSet(), conversions.findAll().map { it.userId }.toSet())
    }

    private fun request(path: String) = HttpRequest.newBuilder(URI.create(baseUrl + path))
        .header("X-Prism-Api-Key", "prism-test-api-key-0123456789abcdef")
    private fun post(path: String, body: Any): HttpRequest = request(path).header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build()

    interface Checkout { fun name(): String }
    @PrismStrategy("control", "checkout") class Control : Checkout { override fun name() = "control" }
    @PrismStrategy("B", "checkout") class Treatment : Checkout { override fun name() = "B" }
    @PrismStrategy("control", "checkout") class DuplicateControl : Checkout { override fun name() = "control" }
}
