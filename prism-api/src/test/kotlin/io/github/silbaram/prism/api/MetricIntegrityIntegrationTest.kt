package io.github.silbaram.prism.api

import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import io.github.silbaram.prism.sdk.PrismClientOptions
import io.github.silbaram.prism.sdk.EvaluationMode
import io.github.silbaram.prism.sdk.PrismClient
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.time.LocalDateTime
import org.junit.jupiter.api.Assertions.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:metrics;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
    "spring.jpa.properties.hibernate.show_sql=false"
])
class MetricIntegrityIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var environment: Environment
    private lateinit var client: PrismClient

    @BeforeEach
    fun setup() {
        conversions.deleteAll()
        impressions.deleteAll()
        experiments.deleteAll()
        client = PrismClient("http://localhost:${environment.getProperty("local.server.port")}", options = PrismClientOptions(evaluationMode = EvaluationMode.REMOTE))
        val experiment = ExperimentEntity(key = "checkout", description = "test", goalEventName = "purchase", status = ExperimentStatus.ACTIVE)
        experiment.addVariant(VariantEntity(name = "A", weight = 100))
        experiments.save(experiment)
    }

    @org.junit.jupiter.api.AfterEach
    fun closeClient() { client.close() }

    @Test
    fun `fresh assignment commits exposure before immediate conversion and duplicate events count once`() {
        repeat(3) { assertEquals("A", client.assign("u", "checkout").variant) }
        repeat(5) { assertTrue(client.trackConversion("u", "checkout", "purchase")) }
        assertTrue(client.trackConversion("u", "checkout", "page_viewed"))
        assertTrue(client.trackConversion("u", "checkout", "payment_failed"))
        assertEquals(3L, impressions.count())
        assertEquals(7L, conversions.count())
        assertEquals(1L, impressions.countImpressionsByVariant("checkout").single()[1])
        assertEquals(1L, conversions.countConversionsByVariant("checkout", "purchase").single()[1])
        val events = conversions.countEventsByVariant("checkout")
        assertEquals(3, events.size)
        assertEquals(5L, events.first { it[0] == "purchase" }[3])
    }

    @Test
    fun `unexposed conversion is explicitly rejected and read only lookup cannot create exposure`() {
        assertEquals(ResponseCode.IMPRESSION_NOT_FOUND.code, client.getAssignment("u", "checkout").resultCode)
        assertFalse(client.trackConversion("u", "checkout", "purchase"))
        assertEquals(0L, impressions.count())
        assertEquals(0L, conversions.count())
        client.assign("u", "checkout")
        assertEquals("A", client.getAssignment("u", "checkout").variant)
        assertEquals(ResponseCode.IMPRESSION_NOT_FOUND.code, client.getAssignment("other", "checkout").resultCode)
        assertEquals(ResponseCode.IMPRESSION_NOT_FOUND.code, client.getAssignment("u", "other").resultCode)
        assertEquals(1L, impressions.count())
    }

    @Test
    fun `goal aggregation excludes other events experiments variants and events before exposure`() {
        val now = LocalDateTime.now()
        impressions.saveAll(listOf(
            ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "u", timestamp = now),
            ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "u", timestamp = now),
            ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "v", timestamp = now)))
        fun event(user: String, name: String = "purchase", variant: String = "A", experiment: String = "checkout", time: LocalDateTime = now.plusSeconds(1)) =
            ConversionLogEntity(experimentKey = experiment, variant = variant, userId = user, eventName = name, timestamp = time)
        conversions.saveAll(listOf(event("u"), event("u"), event("u", "Purchase"), event("v", "failed"),
            event("v", time = now.minusSeconds(1)), event("ghost"), event("u", variant = "B"), event("u", experiment = "other")))
        assertEquals(2L, impressions.countImpressionsByVariant("checkout").single()[1])
        val goal = conversions.countConversionsByVariant("checkout", "purchase").single()
        assertEquals("A", goal[0])
        assertEquals(1L, goal[1])
        assertTrue(conversions.countConversionsByVariant("checkout", "missing-goal").isEmpty())
    }

    @Test
    fun `conversion uses latest inserted exposure despite inverted server timestamps`() {
        val now = LocalDateTime.now()
        impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "u", timestamp = now.plusMinutes(2)))
        val latest = impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "B", userId = "u", timestamp = now.plusMinutes(1)))
        assertEquals("B", client.getAssignment("u", "checkout").variant)
        assertTrue(client.trackConversion("u", "checkout", "purchase"))
        val conversion = conversions.findAll().single()
        assertEquals("B", conversion.variant)
        assertEquals(latest.id, conversion.impressionId)
        assertTrue(conversion.timestamp < latest.timestamp)
        val goal = conversions.countConversionsByVariant("checkout", "purchase").single()
        assertEquals("B", goal[0])
        assertEquals(1L, goal[1])
        val event = conversions.countEventsByVariant("checkout").single()
        assertEquals("B", event[1])
        assertEquals(1L, event[3])
    }

    @Test
    fun `linked conversion cannot use an exposure belonging to another identity or variant`() {
        val exposure = impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "u"))
        conversions.saveAll(listOf(
            ConversionLogEntity(experimentKey = "checkout", variant = "A", userId = "other", eventName = "purchase", impressionId = exposure.id),
            ConversionLogEntity(experimentKey = "checkout", variant = "B", userId = "u", eventName = "purchase", impressionId = exposure.id),
            ConversionLogEntity(experimentKey = "other", variant = "A", userId = "u", eventName = "purchase", impressionId = exposure.id)))
        assertTrue(conversions.countConversionsByVariant("checkout", "purchase").isEmpty())
        assertTrue(conversions.countEventsByVariant("checkout").isEmpty())
        assertTrue(conversions.countEventsByVariant("other").isEmpty())
    }

    @Test
    fun `case sensitive user identities cannot share exposure or collapse the CVR denominator`() {
        client.assign("User-1", "checkout")
        assertFalse(client.trackConversion("user-1", "checkout", "purchase"))
        assertEquals(ResponseCode.IMPRESSION_NOT_FOUND.code, client.getAssignment("user-1", "checkout").resultCode)
        client.assign("user-1", "checkout")
        assertTrue(client.trackConversion("user-1", "checkout", "purchase"))
        assertEquals(2L, impressions.countImpressionsByVariant("checkout").single()[1])
        assertEquals(1L, conversions.countConversionsByVariant("checkout", "purchase").single()[1])
    }
}
