package io.github.silbaram.prism.admin

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.dto.*
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "spring.datasource.url=jdbc:h2:mem:adminmetrics;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never",
    "spring.jpa.properties.hibernate.show_sql=false"
])
class AdminMetricsIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var changes: ExperimentChangeRepository
    @Autowired lateinit var service: ExperimentService
    @Autowired lateinit var transactions: PlatformTransactionManager
    private val http = HttpClient.newHttpClient()
    private fun uri(path: String) = URI.create("http://localhost:${environment.getProperty("local.server.port")}$path")
    private fun get(path: String): String {
        val response = http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }
    private fun post(path: String, values: Map<String, String>): HttpResponse<String> {
        val form = values.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        return http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "text/html")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())
    }
    @BeforeEach
    fun clean() { conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll(); changes.deleteAll() }

    @Test
    fun `goal form binds saves updates and renders metric and event pages`() {
        assertTrue(get("/admin/experiments/new").contains("name=\"goalEventName\""))
        val fields = mapOf("key" to "checkout", "description" to "test", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100")
        val created = post("/admin/experiments", fields)
        assertEquals(302, created.statusCode(), created.body())
        val experiment = experiments.findByKey("checkout")!!
        assertEquals("purchase", experiment.goalEventName)
        assertEquals(302, post("/admin/experiments/${experiment.id}", fields + ("goalEventName" to "signup")).statusCode())
        assertEquals("signup", experiments.findByKey("checkout")!!.goalEventName)
        assertEquals(302, post("/admin/experiments/${experiment.id}", fields).statusCode())
        impressions.save(ImpressionLogEntity(experimentKey = "checkout", variant = "A", userId = "u"))
        conversions.save(ConversionLogEntity(experimentKey = "checkout", variant = "A", userId = "u", eventName = "purchase"))
        val detail = get("/admin/experiments/${experiment.id}")
        assertTrue(detail.contains("100.00%"))
        assertTrue(detail.contains("95% 신뢰구간"))
        assertFalse(detail.contains("Winner"))
        assertTrue(get("/admin/experiments/${experiment.id}/events").contains("purchase"))
        assertTrue(get("/admin/simulator").contains("checkout"))
        assertTrue(get("/admin/experiments/${experiment.id}/edit").contains("purchase"))
        val updated = post("/admin/experiments/${experiment.id}", fields + ("goalEventName" to "signup"))
        assertEquals(400, updated.statusCode(), updated.body())
        assertEquals("purchase", experiments.findByKey("checkout")!!.goalEventName)
    }

    @Test
    fun `legacy goals and variants without samples render as unmeasured`() {
        val legacy = ExperimentEntity(key = "legacy", description = "")
        legacy.addVariant(VariantEntity(name = "A", weight = 100))
        val saved = experiments.save(legacy)
        val html = get("/admin/experiments/${saved.id}")
        assertTrue(html.contains("미설정"))
        assertTrue(html.contains("—"))
        assertFalse(html.contains("NaN"))
        assertTrue(get("/admin/experiments/${saved.id}/events").contains("이벤트 데이터가 없습니다"))
    }

    @Test
    fun `duplicate key containing HTML is returned as UTF8 plain text even for a browser request`() {
        val key = "<script>alert('한글')</script>"
        val fields = mapOf("key" to key, "description" to "test", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100")
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val response = post("/admin/experiments", fields)
        assertEquals(400, response.statusCode())
        assertEquals("text/plain;charset=UTF-8", response.headers().firstValue("Content-Type").orElseThrow())
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow())
        assertEquals("Experiment with key $key already exists", response.body())
        assertEquals(1L, experiments.count())
    }

    @Test
    fun `invalid goal submissions receive a clear client error without creating an experiment`() {
        val response = post("/admin/experiments", mapOf("key" to "invalid", "description" to "test",
            "goalEventName" to " ", "variants[0].name" to "A", "variants[0].weight" to "100"))
        assertEquals(400, response.statusCode())
        assertTrue(response.body().contains("목표 이벤트"))
        assertEquals(0L, experiments.count())
    }

    @Test
    fun `activation freezes allocation and history is transactional rendered and retained`() {
        val fields = mapOf("key" to "locked", "description" to "before", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "50",
            "variants[1].name" to "B", "variants[1].weight" to "50")
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val id = experiments.findByKey("locked")!!.id!!
        val active = fields + ("status" to "ACTIVE")
        assertEquals(302, post("/admin/experiments/$id", active).statusCode())
        assertTrue(experiments.findById(id).orElseThrow().configurationLocked)
        assertFalse(get("/admin/experiments").contains("action=\"/admin/experiments/$id/delete\""))
        assertEquals(2, changes.findTop50ByExperimentIdOrderByIdDesc(id).size)
        val edit = get("/admin/experiments/$id/edit")
        assertTrue(edit.contains("설정은 잠겨"))
        assertTrue(edit.contains("readonly"))
        listOf(
            active + mapOf("variants[0].weight" to "10", "variants[1].weight" to "90"),
            active + mapOf("variants[0].name" to "B", "variants[1].name" to "A"),
            active + ("key" to "renamed"), active + ("goalEventName" to "signup"),
            active + ("targetingRules[0].expression" to "country == 'KR'"),
            active + ("status" to "DRAFT")
        ).forEach { assertEquals(400, post("/admin/experiments/$id", it).statusCode()) }
        assertEquals(2, changes.findTop50ByExperimentIdOrderByIdDesc(id).size)
        assertEquals(302, post("/admin/experiments/$id", active + ("status" to "PAUSED")).statusCode())
        assertEquals(400, post("/admin/experiments/$id", fields).statusCode())
        assertEquals(400, post("/admin/experiments/$id", active + mapOf("status" to "PAUSED", "variants[0].name" to "C")).statusCode())
        val ended = active + mapOf("status" to "ENDED", "description" to "<script>changed</script>")
        assertEquals(302, post("/admin/experiments/$id", ended).statusCode())
        assertEquals(400, post("/admin/experiments/$id", active).statusCode())
        assertEquals(400, post("/admin/experiments/$id/delete", emptyMap()).statusCode())
        val history = changes.findTop50ByExperimentIdOrderByIdDesc(id)
        assertEquals(4, history.size)
        assertTrue(history.first().beforeSnapshot!!.contains("PAUSED"))
        assertTrue(history.first().afterSnapshot!!.contains("ENDED"))
        val html = get("/admin/experiments/$id")
        assertTrue(html.contains("설정 변경 이력"))
        assertFalse(html.contains("<script>changed</script>"))
        assertEquals(302, post("/admin/experiments/$id", ended).statusCode())
        assertEquals(4, changes.findTop50ByExperimentIdOrderByIdDesc(id).size)

        val draft = service.createExperiment(ExperimentCreateDto("draft", "", "purchase", listOf(VariantDto("A", 100))))
        service.deleteExperiment(draft.id!!)
        assertEquals(listOf("DELETE", "CREATE"), changes.findTop50ByExperimentIdOrderByIdDesc(draft.id!!).map { it.action })
    }

    @Test
    fun `concurrent start prevents a stale draft edit and audit rolls back with mutation`() {
        val draft = service.createExperiment(ExperimentCreateDto("race", "before", "purchase",
            listOf(VariantDto("A", 50), VariantDto("B", 50))))
        val id = draft.id!!
        val tx = TransactionTemplate(transactions)
        assertThrows(IllegalStateException::class.java) {
            tx.executeWithoutResult { service.startExperiment(id); throw IllegalStateException("rollback") }
        }
        assertEquals(ExperimentStatus.DRAFT, experiments.findById(id).orElseThrow().status)
        assertEquals(1, changes.findTop50ByExperimentIdOrderByIdDesc(id).size)
        val workers = Executors.newSingleThreadExecutor()
        try {
            lateinit var stale: Future<*>
            tx.executeWithoutResult {
                service.startExperiment(id)
                val entered = CountDownLatch(1)
                stale = workers.submit {
                    entered.countDown()
                    service.updateExperiment(id, ExperimentUpdateDto("race", "after", "purchase", ExperimentStatus.DRAFT,
                        listOf(VariantDto("A", 10), VariantDto("B", 90))))
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertThrows(TimeoutException::class.java) { stale.get(100, TimeUnit.MILLISECONDS) }
            }
            assertInstanceOf(IllegalArgumentException::class.java,
                assertThrows(ExecutionException::class.java) { stale.get(5, TimeUnit.SECONDS) }.cause)
            assertEquals(ExperimentStatus.ACTIVE, experiments.findById(id).orElseThrow().status)
            assertEquals(2, changes.findTop50ByExperimentIdOrderByIdDesc(id).size)
        } finally { workers.shutdownNow() }
    }

    @Test
    fun `invalid historical locked configuration can stop without rewriting its original values`() {
        val experiment = experiments.save(ExperimentEntity(key = "old", description = "", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "A", weight = 40)); addVariant(VariantEntity(name = "B", weight = 40))
            addTargetingRule(TargetingRuleEntity(expression = ""))
        })
        val form = mapOf("key" to "old", "description" to "stopped", "goalEventName" to "", "status" to "ENDED",
            "variants[0].name" to "A", "variants[0].weight" to "40", "variants[1].name" to "B", "variants[1].weight" to "40",
            "targetingRules[0].expression" to "")
        assertEquals(302, post("/admin/experiments/${experiment.id}", form).statusCode())
        val stopped = experiments.findById(experiment.id!!).orElseThrow()
        assertEquals(ExperimentStatus.ENDED, stopped.status)
        assertTrue(stopped.configurationLocked)
        assertNull(stopped.goalEventName)
        assertTrue(get("/admin/experiments/${experiment.id}").contains("INVALID_DATA"))
        assertEquals(1, changes.findTop50ByExperimentIdOrderByIdDesc(experiment.id!!).size)
    }

    @Test
    fun `SRM and crossover warnings suppress group comparisons in rendered admin`() {
        val experiment = experiments.save(ExperimentEntity(key = "srm", description = "", goalEventName = "purchase",
            status = ExperimentStatus.ENDED, configurationLocked = true).apply {
            addVariant(VariantEntity(name = "A", weight = 50)); addVariant(VariantEntity(name = "B", weight = 50))
            addVariant(VariantEntity(name = "disabled", weight = 0))
        })
        fun expose(variant: String, count: Int) = impressions.saveAll((0 until count).map {
            ImpressionLogEntity(experimentKey = "srm", variant = variant, userId = "$variant-$it")
        })
        expose("A", 100); expose("B", 100)
        conversions.saveAll((0 until 10).map { ConversionLogEntity(experimentKey = "srm", variant = "A", userId = "A-$it", eventName = "purchase") } +
            (0 until 30).map { ConversionLogEntity(experimentKey = "srm", variant = "B", userId = "B-$it", eventName = "purchase") })
        // Repeat rows must not cause SRM or change the user-level conversion test.
        expose("A", 100)
        val balanced = get("/admin/experiments/${experiment.id}")
        assertTrue(balanced.contains("SRM · PASS"))
        assertTrue(balanced.contains("카이제곱 = 12.5000"))
        expose("A", 300)
        val mismatch = get("/admin/experiments/${experiment.id}")
        assertTrue(mismatch.contains("SRM 경고"))
        assertFalse(mismatch.contains("카이제곱 ="))
        impressions.save(ImpressionLogEntity(experimentKey = "srm", variant = "B", userId = "A-0"))
        val crossover = get("/admin/experiments/${experiment.id}")
        assertTrue(crossover.contains("동일 사용자가 여러 변형"))
        assertFalse(crossover.contains("카이제곱 ="))
    }
}
