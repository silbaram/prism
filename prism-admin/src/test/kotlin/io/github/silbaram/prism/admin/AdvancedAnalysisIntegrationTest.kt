package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.service.*
import io.github.silbaram.prism.admin.service.dto.*
import io.github.silbaram.prism.api.event.EventIngestionService
import io.github.silbaram.prism.api.pipeline.PipelineConfiguration
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import java.net.*
import java.net.http.*
import java.time.*
import java.util.UUID
import java.util.concurrent.*

@SpringBootTest(classes = [PrismAdminApplication::class], webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.admin.username=admin", "prism.schedule.enabled=false", "prism.analysis.finalization-enabled=false",
    "prism.admin.password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "spring.datasource.url=jdbc:h2:mem:advanced-analysis;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "spring.jpa.properties.hibernate.show_sql=false"
])
@Import(EventIngestionService::class, PipelineConfiguration::class)
class AdvancedAnalysisIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var experimentService: ExperimentService
    @Autowired lateinit var plans: AnalysisPlanRepository
    @Autowired lateinit var planService: AnalysisPlanService
    @Autowired lateinit var observations: AnalysisObservationRepository
    @Autowired lateinit var analytics: AdvancedAnalysisService
    @Autowired lateinit var finalizer: AnalysisFinalizer
    @Autowired lateinit var ingestion: EventIngestionService
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var receipts: EventReceiptRepository
    @Autowired lateinit var changes: ExperimentChangeRepository
    @Autowired lateinit var environment: Environment

    @BeforeEach fun reset() {
        observations.deleteAll(); plans.deleteAll(); conversions.deleteAll(); impressions.deleteAll()
        receipts.deleteAll(); experiments.deleteAll(); changes.deleteAll()
    }
    private fun create() = experimentService.createExperiment(ExperimentCreateDto("advanced", "", "purchase",
        listOf(VariantDto("A", 50), VariantDto("B", 50))))

    @Test fun `nanosecond input uses persisted precision for repeated exposures and late conversion boundaries`() {
        val experiment = create()
        planService.create(experiment.id!!, AnalysisPlanInput("A", 1, 1))
        experimentService.startExperiment(experiment.id!!)
        val start = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val exposure = ClientEvent(UUID.randomUUID().toString(), "exposure", "precision", "advanced", "A",
            start.plusNanos(123456789).toString(), "a".repeat(64))
        val boundary = exposure.copy(eventId = UUID.randomUUID().toString(), userId = "boundary", timestamp = start.toString())
        assertTrue(ingestion.ingest(listOf(exposure, boundary)).results.all { it.status == EventStatus.ACCEPTED })
        finalizer.finalizeDue(LocalDateTime.ofInstant(start.plusSeconds(10800), ZoneOffset.UTC))
        assertEquals(EventStatus.ACCEPTED, ingestion.ingest(listOf(exposure.copy(eventId = UUID.randomUUID().toString()))).results.single().status)
        assertNull(observations.findAll().single { it.userId == "precision" }.invalidReason,
            "A repeat of the same occurred-at value must not become an earlier exposure after database rounding")
        val late = boundary.copy(eventId = UUID.randomUUID().toString(), type = "conversion",
            timestamp = start.plusSeconds(3600).minusNanos(1).toString(), eventName = "purchase", exposureEventId = boundary.eventId)
        assertEquals(EventStatus.ACCEPTED, ingestion.ingest(listOf(late)).results.single().status)
        val row = observations.findAll().single { it.userId == "boundary" }
        val eligible = conversions.countWindowConversions("advanced", "boundary", "A", "purchase", row.exposedAt, row.outcomeEndsAt)
        assertEquals(if (eligible > 0) "LATE_CONVERSION_AFTER_FINALIZATION" else null, row.invalidReason,
            "Late-event guard must use the same timestamp precision as the persisted conversion query")
    }

    @Test fun `out of range baseline time is rejected without aborting a batch`() {
        val experiment = create()
        planService.create(experiment.id!!, AnalysisPlanInput("A"))
        experimentService.startExperiment(experiment.id!!)
        val event = ClientEvent(UUID.randomUUID().toString(), "exposure", "range", "advanced", "A", Instant.now().toString(), "a".repeat(64),
            analysis = ExposureAnalysisContext(baselineValue = 1.0, baselineMeasuredAt = "+1000000000-01-01T00:00:00Z"))
        val valid = event.copy(eventId = UUID.randomUUID().toString(), userId = "valid", analysis = null)
        assertEquals(listOf(EventStatus.REJECTED, EventStatus.ACCEPTED), ingestion.ingest(listOf(event, valid)).results.map { it.status })
        assertEquals(1, impressions.count())
    }

    @Test fun `multiple finalizers racing a conversion cannot leave an unflagged false outcome`() {
        val experiment = create()
        planService.create(experiment.id!!, AnalysisPlanInput("A", 1, 1))
        experimentService.startExperiment(experiment.id!!)
        val start = Instant.now().plusSeconds(1)
        val mature = LocalDateTime.ofInstant(start.plusSeconds(10800), ZoneOffset.UTC)
        val pool = Executors.newFixedThreadPool(3)
        try {
            repeat(12) { index ->
                val exposure = ClientEvent(UUID.randomUUID().toString(), "exposure", "race-$index", "advanced", "A", start.toString(), "a".repeat(64))
                assertEquals(EventStatus.ACCEPTED, ingestion.ingest(listOf(exposure)).results.single().status)
                val goal = exposure.copy(eventId = UUID.randomUUID().toString(), type = "conversion", eventName = "purchase",
                    exposureEventId = exposure.eventId, timestamp = start.plusSeconds(60).toString())
                val gate = CountDownLatch(3)
                fun ready() { gate.countDown(); check(gate.await(10, TimeUnit.SECONDS)) }
                val first = pool.submit<Int> { ready(); finalizer.finalizeDue(mature) }
                val second = pool.submit<Int> { ready(); finalizer.finalizeDue(mature) }
                val writer = pool.submit<EventStatus> { ready(); ingestion.ingest(listOf(goal)).results.single().status }
                assertEquals(EventStatus.ACCEPTED, writer.get(15, TimeUnit.SECONDS))
                assertEquals(1, first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS))
                val row = observations.findAll().single { it.userId == exposure.userId }
                assertNotNull(row.finalizedAt)
                assertTrue(row.converted == true || row.invalidReason == "LATE_CONVERSION_AFTER_FINALIZATION")
                assertEquals(1, conversions.countWindowConversions("advanced", row.userId, "A", "purchase", row.exposedAt, row.outcomeEndsAt))
            }
        } finally { pool.shutdownNow() }
    }

    @Test fun `a planned draft can change its description without starting or unlocking its design`() {
        val experiment = create()
        planService.create(experiment.id!!, AnalysisPlanInput("A"))
        val update = ExperimentUpdateDto("advanced", "reviewed description", "purchase", ExperimentStatus.DRAFT,
            listOf(VariantDto("A", 50), VariantDto("B", 50)))
        val saved = experimentService.updateExperiment(experiment.id!!, update)
        assertEquals(ExperimentStatus.DRAFT, saved.status)
        assertEquals("reviewed description", saved.description)
        assertTrue(saved.configurationLocked)
        assertThrows(IllegalArgumentException::class.java) {
            experimentService.updateExperiment(experiment.id!!, update.copy(goalEventName = "changed-goal"))
        }
        withAdmin { http, url ->
            val page = http.send(HttpRequest.newBuilder(URI.create("$url/admin/experiments/${experiment.id}/edit")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(200, page.statusCode())
            val option = Regex("<option[^>]*value=\"DRAFT\"[^>]*>").find(page.body())!!.value
            assertFalse(option.contains("disabled"), option)
        }
        experimentService.startExperiment(experiment.id!!)
        assertThrows(IllegalArgumentException::class.java) { experimentService.updateExperiment(experiment.id!!, update) }
    }

    @Test fun `reports include every finalized page and finalization leaves its remaining batch pending`() {
        val experiment = create()
        planService.create(experiment.id!!, AnalysisPlanInput("A", 1, 1, mapOf("device" to listOf("mobile"))))
        experimentService.startExperiment(experiment.id!!)
        val start = Instant.now().plusSeconds(1)
        val events = (0 until 1002).map { index -> ClientEvent(UUID.randomUUID().toString(), "exposure", "page-$index", "advanced",
            if (index % 2 == 0) "A" else "B", start.toString(), "a".repeat(64),
            analysis = ExposureAnalysisContext(mapOf("device" to "mobile"))) }
        events.chunked(100).forEach { batch -> assertTrue(ingestion.ingest(batch).results.all { it.status == EventStatus.ACCEPTED }) }
        val mature = LocalDateTime.ofInstant(start.plusSeconds(10800), ZoneOffset.UTC)
        assertEquals(500, finalizer.finalizeDue(mature))
        assertEquals(502, analytics.report(experiment.id!!).pendingUsers)
        assertEquals(500, finalizer.finalizeDue(mature))
        assertEquals(2, finalizer.finalizeDue(mature))
        assertEquals(0, finalizer.finalizeDue(mature))
        val report = analytics.report(experiment.id!!)
        assertTrue(report.available)
        assertEquals(0, report.pendingUsers)
        assertEquals(listOf(501L, 501L), report.groups.map { it.users })
        assertEquals(1002, report.segments.filter { it.value == "mobile" }.sumOf { it.group.users })
    }

    @Test fun `predeclared plan matures per user outcomes renders all analyses and blocks later label changes`() {
        val experiment = create()
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(1).withNano(0)
        val input = AnalysisPlanInput("A", 1, 1, mapOf("device" to listOf("mobile", "desktop")), true, cutoff, "7-day purchase count")
        planService.create(experiment.id!!, input)
        assertThrows(IllegalArgumentException::class.java) { planService.create(experiment.id!!, input) }
        experimentService.startExperiment(experiment.id!!)
        val start = Instant.now().plusSeconds(1)
        var unconverted: ClientEvent? = null
        for (variant in listOf("A", "B")) repeat(60) { index ->
            val x = if (index % 10 >= 5) 1.0 else 0.0
            val exposure = ClientEvent(UUID.randomUUID().toString(), "exposure", "$variant-$index", "advanced", variant,
                start.toString(), "a".repeat(64), analysis = ExposureAnalysisContext(mapOf("device" to "mobile"), x,
                    cutoff.minusHours(1).toInstant(ZoneOffset.UTC).toString()))
            val converted = x == 1.0 || (variant == "B" && index % 10 == 4)
            val events = if (converted) listOf(exposure, exposure.copy(eventId = UUID.randomUUID().toString(), type = "conversion",
                timestamp = start.plusSeconds(60).toString(), eventName = "purchase", exposureEventId = exposure.eventId, analysis = null)) else listOf(exposure)
            assertTrue(ingestion.ingest(events).results.all { it.status == EventStatus.ACCEPTED })
            assertTrue(ingestion.ingest(events).results.all { it.status == EventStatus.DUPLICATE })
            if (variant == "A" && index == 0) unconverted = exposure
        }
        assertEquals(120, analytics.report(experiment.id!!).pendingUsers)
        assertEquals(0, finalizer.finalizeDue(LocalDateTime.ofInstant(start.plusSeconds(3600), ZoneOffset.UTC)))
        assertEquals(120, finalizer.finalizeDue(LocalDateTime.ofInstant(start.plusSeconds(10800), ZoneOffset.UTC)))
        val report = analytics.report(experiment.id!!)
        assertTrue(report.available, report.message)
        assertEquals(listOf(60L, 60L), report.groups.map { it.users })
        assertEquals(listOf(30L, 36L), report.groups.map { it.conversions })
        assertNotNull(report.comparisons.single().sequential)
        assertTrue(report.comparisons.single().bayesian!!.probabilityBetter > 0.5)
        assertTrue(report.comparisons.single().cuped!!.available)
        assertEquals(120, report.segments.filter { it.value == "mobile" }.sumOf { it.group.users })
        assertEquals(0, report.segments.filter { it.value == null }.sumOf { it.group.users })
        val original = unconverted!!
        val repeated = original.copy(eventId = UUID.randomUUID().toString(), timestamp = start.plusSeconds(10).toString(),
            analysis = ExposureAnalysisContext(mapOf("device" to "desktop"), 999.0, original.analysis!!.baselineMeasuredAt))
        assertEquals(EventStatus.ACCEPTED, ingestion.ingest(listOf(repeated)).results.single().status)
        assertEquals(report.groups, analytics.report(experiment.id!!).groups)
        verifyPage(experiment.id!!)
        val late = original.copy(eventId = UUID.randomUUID().toString(), type = "conversion", timestamp = start.plusSeconds(60).toString(),
            eventName = "purchase", exposureEventId = original.eventId, analysis = null)
        assertEquals(EventStatus.ACCEPTED, ingestion.ingest(listOf(late)).results.single().status)
        val blocked = analytics.report(experiment.id!!)
        assertFalse(blocked.available)
        assertEquals(1, blocked.invalidUsers)
        assertNull(blocked.comparisons.single().sequential)
        assertNull(blocked.comparisons.single().bayesian)
    }

    @Test fun `plan creation is limited to unstarted experiments with valid predefined cohorts`() {
        val experiment = create()
        assertThrows(IllegalArgumentException::class.java) { planService.create(experiment.id!!, AnalysisPlanInput("missing")) }
        assertThrows(IllegalArgumentException::class.java) { planService.create(experiment.id!!, AnalysisPlanInput("A", outcomeHours = 0)) }
        assertThrows(IllegalArgumentException::class.java) { planService.create(experiment.id!!, AnalysisPlanInput("A", cupedEnabled = true)) }
        experimentService.startExperiment(experiment.id!!)
        assertThrows(IllegalArgumentException::class.java) { planService.create(experiment.id!!, AnalysisPlanInput("A")) }
        assertEquals(0, plans.count())
    }

    @Test fun `analysis plan form validates timestamps and locks the persisted design over HTTP`() {
        val experiment = create()
        withAdmin { http, url ->
            fun form() = http.send(HttpRequest.newBuilder(URI.create("$url/admin/experiments/${experiment.id}/analysis")).GET().build(), HttpResponse.BodyHandlers.ofString())
            val page = form()
            assertEquals(200, page.statusCode())
            val token = Regex("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").find(page.body())!!.groupValues[1]
            fun post(values: Map<String, String>): Int {
                val payload = (values + ("_csrf" to token)).entries.joinToString("&") {
                    URLEncoder.encode(it.key, Charsets.UTF_8) + "=" + URLEncoder.encode(it.value, Charsets.UTF_8)
                }
                return http.send(HttpRequest.newBuilder(URI.create("$url/admin/experiments/${experiment.id}/analysis/plan"))
                    .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString()).statusCode()
            }
            val fields = mapOf("controlVariant" to "A", "outcomeHours" to "24", "latenessHours" to "24", "segments" to "device=mobile,desktop")
            assertEquals(400, post(fields + ("baselineCutoff" to "not-a-date")))
            assertFalse(plans.existsById(experiment.id!!))
            assertEquals(302, post(fields))
            assertTrue(experiments.findById(experiment.id!!).orElseThrow().configurationLocked)
            assertEquals(400, post(fields))
            assertEquals(200, form().statusCode())
        }
    }

    @Test fun `first exposure controls metadata and window while invalid baselines and late earlier exposures remain visible`() {
        val experiment = create()
        val cutoff = LocalDateTime.now(ZoneOffset.UTC).minusDays(1).withNano(0)
        planService.create(experiment.id!!, AnalysisPlanInput("A", 1, 1, mapOf("device" to listOf("mobile")),
            true, cutoff, "previous week purchases"))
        experimentService.startExperiment(experiment.id!!)
        val start = Instant.now().plusSeconds(1).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
        fun send(event: ClientEvent) = assertEquals(EventStatus.ACCEPTED, ingestion.ingest(listOf(event)).results.single().status)
        fun exposure(user: String, offset: Long, context: ExposureAnalysisContext? = null) = ClientEvent(
            UUID.randomUUID().toString(), "exposure", user, "advanced", "A", start.plusSeconds(offset).toString(), "a".repeat(64), analysis = context)
        val first = exposure("reordered", 10, ExposureAnalysisContext(mapOf("device" to "unregistered"), 9.0,
            cutoff.plusHours(1).toInstant(ZoneOffset.UTC).toString()))
        send(first)
        assertNull(observations.findAll().single().baselineValue)
        assertEquals("{}", observations.findAll().single().segmentsJson)
        send(exposure("reordered", 0, ExposureAnalysisContext(mapOf("device" to "mobile"), 0.0,
            cutoff.toInstant(ZoneOffset.UTC).toString())))
        assertEquals(0.0, observations.findAll().single().baselineValue)
        assertEquals(LocalDateTime.ofInstant(start, ZoneOffset.UTC), observations.findAll().single().exposedAt)
        // Exactly the end is excluded, even when received before finalization.
        send(first.copy(eventId = UUID.randomUUID().toString(), type = "conversion", timestamp = start.plusSeconds(3600).toString(),
            eventName = "purchase", exposureEventId = first.eventId, analysis = null))
        val multiple = exposure("multiple", 0)
        send(multiple)
        send(multiple.copy(eventId = UUID.randomUUID().toString(), variant = "B"))
        assertEquals("MULTIPLE_VARIANTS", observations.findAll().single { it.userId == "multiple" }.invalidReason)
        assertEquals(2, finalizer.finalizeDue(LocalDateTime.ofInstant(start.plusSeconds(7200), ZoneOffset.UTC)))
        assertFalse(observations.findAll().single { it.userId == "reordered" }.converted!!)
        send(first.copy(eventId = UUID.randomUUID().toString(), type = "conversion", timestamp = start.plusSeconds(3601).toString(),
            eventName = "purchase", exposureEventId = first.eventId, analysis = null))
        assertNull(observations.findAll().single { it.userId == "reordered" }.invalidReason)
        send(exposure("reordered", -1))
        assertEquals("LATE_EARLIER_EXPOSURE", observations.findAll().single { it.userId == "reordered" }.invalidReason)
    }

    private fun verifyPage(id: Long) = withAdmin { http, url ->
        val response = http.send(HttpRequest.newBuilder(URI.create("$url/admin/experiments/$id/analysis")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        listOf("Sequential testing", "Bayesian 분석", "CUPED", "세그먼트별 분석", "mobile").forEach { assertTrue(response.body().contains(it)) }
    }

    private fun withAdmin(block: (HttpClient, String) -> Unit) {
        val url = "http://localhost:${environment.getProperty("local.server.port")}"
        HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build().use { http ->
            val page = http.send(HttpRequest.newBuilder(URI.create("$url/login")).GET().build(), HttpResponse.BodyHandlers.ofString())
            val token = Regex("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").find(page.body())!!.groupValues[1]
            val login = "username=admin&password=prism-test-password&_csrf=" + URLEncoder.encode(token, Charsets.UTF_8)
            assertEquals(302, http.send(HttpRequest.newBuilder(URI.create("$url/login")).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(login)).build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            block(http, url)
        }
    }
}
