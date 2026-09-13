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
    "prism.admin.username=admin", "prism.schedule.enabled=false",
    "prism.admin.password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "prism.admin.viewer-username=viewer",
    "prism.admin.viewer-password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
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
    @Autowired lateinit var dataSource: javax.sql.DataSource
    private val http = HttpClient.newBuilder().cookieHandler(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build()
    private fun uri(path: String) = URI.create("http://localhost:${environment.getProperty("local.server.port")}$path")
    private fun get(path: String): String {
        val response = http.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }
    private fun csrf(html: String): String = Regex("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").find(html)!!.groupValues[1]
    private fun post(path: String, values: Map<String, String>): HttpResponse<String> =
        postRaw(http, path, values + ("_csrf" to csrf(get("/admin/experiments/new"))))
    private fun postRaw(client: HttpClient, path: String, values: Map<String, String>): HttpResponse<String> {
        val form = values.entries.joinToString("&") { (k, v) ->
            URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)
        }
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "text/html")
            .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString())
    }
    @BeforeEach
    fun clean() {
        conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll(); changes.deleteAll()
        val loginPage = http.send(HttpRequest.newBuilder(uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString())
        val login = postRaw(http, "/login", mapOf("username" to "admin", "password" to "prism-test-password", "_csrf" to csrf(loginPage.body())))
        assertEquals(302, login.statusCode(), login.body())
        assertFalse(login.headers().firstValue("Location").orElse("").contains("error"))
    }

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
    fun `duplicate key containing HTML is escaped in a form error and preserves the submitted key`() {
        val key = "<script>alert('한글')</script>"
        val fields = mapOf("key" to key, "description" to "test", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100")
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val response = post("/admin/experiments", fields)
        assertEquals(400, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElseThrow().startsWith("text/html"))
        assertEquals("nosniff", response.headers().firstValue("X-Content-Type-Options").orElseThrow())
        assertTrue(response.body().contains("already exists"))
        assertTrue(response.body().contains("&lt;script&gt;"))
        assertFalse(response.body().contains(key))
        assertTrue(response.body().contains("name=\"key\""))
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
    fun `database constraint failures render a sanitized conflict page`() {
        dataSource.connection.use { connection -> connection.createStatement().use {
            it.execute("ALTER TABLE experiments ADD CONSTRAINT review_conflict CHECK (description <> 'db-conflict')")
        } }
        try {
            val response = post("/admin/experiments", mapOf("key" to "conflict", "description" to "db-conflict",
                "goalEventName" to "purchase", "variants[0].name" to "A", "variants[0].weight" to "100"))
            assertEquals(409, response.statusCode(), response.body())
            assertTrue(response.body().contains("다른 변경과 충돌"))
            assertFalse(response.body().contains("review_conflict", ignoreCase = true))
            assertFalse(response.body().contains("insert into", ignoreCase = true))
            assertEquals(0L, experiments.count())
            assertEquals(0L, changes.count())
        } finally {
            dataSource.connection.use { connection -> connection.createStatement().use {
                it.execute("ALTER TABLE experiments DROP CONSTRAINT review_conflict")
            } }
        }
    }

    @Test
    fun `form errors preserve input allow correction and never partially update persisted state`() {
        val fields = mapOf("key" to "retained", "description" to "<script>keep me</script>", "goalEventName" to "purchase",
            "variants[0].name" to "control", "variants[0].weight" to "20",
            "variants[1].name" to "treatment", "variants[1].weight" to "30", "trafficAllocation" to "5",
            "startsAt" to "2030-01-01T00:00", "endsAt" to "2030-02-01T00:00", "guardrailEvents" to "failure\ncrash")
        val rejected = post("/admin/experiments", fields)
        assertEquals(400, rejected.statusCode(), rejected.body())
        assertTrue(rejected.body().contains("합계는 100"))
        assertTrue(rejected.body().contains("&lt;script&gt;keep me&lt;/script&gt;"))
        for (value in listOf("retained", "control", "treatment", "20", "30", "5", "2030-01-01T00:00", "2030-02-01T00:00")) {
            assertTrue(rejected.body().contains("value=\"$value\""), value)
        }
        assertTrue(rejected.body().contains("failure\ncrash"))
        assertEquals(0L, experiments.count())
        val valid = fields + mapOf("variants[0].weight" to "50", "variants[1].weight" to "50")
        assertEquals(302, postRaw(http, "/admin/experiments", valid + ("_csrf" to csrf(rejected.body()))).statusCode())
        val id = experiments.findByKey("retained")!!.id!!
        val invalidNumber = post("/admin/experiments/$id", valid + ("trafficAllocation" to "abc"))
        assertEquals(400, invalidNumber.statusCode(), invalidNumber.body())
        assertTrue(invalidNumber.body().contains("value=\"abc\""))
        assertTrue(invalidNumber.body().contains("action=\"/admin/experiments/$id\""))
        assertEquals(5, experiments.findById(id).orElseThrow().trafficAllocation)
        val invalidStatus = post("/admin/experiments/$id", valid + ("status" to "UNKNOWN"))
        assertEquals(400, invalidStatus.statusCode(), invalidStatus.body())
        assertTrue(invalidStatus.body().contains("value=\"UNKNOWN\""))
        assertEquals(1, changes.findTop50ByExperimentIdOrderByIdDesc(id).size)
    }

    @Test
    fun `invalid collection indices and field types return client errors without persistence`() {
        val fields = mapOf("key" to "binding", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100")
        listOf(
            fields + ("variants[256].name" to "B"),
            fields + ("targetingRules[-1].expression" to "true"),
            fields + ("variants[2].name" to "B"),
            fields + ("variants[99999999999999999999].name" to "B"),
            fields + ("trafficAllocation" to "abc"),
            fields + ("status" to "UNKNOWN")
        ).forEach { input ->
            val response = post("/admin/experiments", input)
            assertEquals(400, response.statusCode(), response.body())
        }
        assertEquals(0L, experiments.count())
        assertEquals(0L, changes.count())
        assertEquals(404, post("/admin/experiments/999999999", fields + ("variants[256].name" to "B")).statusCode())
    }

    @Test
    fun `creation offers only draft status and rejects silently ignored transitions`() {
        val page = get("/admin/experiments/new")
        assertFalse(page.contains("value=\"SCHEDULED\""))
        val fields = mapOf("key" to "create-status", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100", "startsAt" to "2030-01-01T00:00")
        for (status in listOf("SCHEDULED", "ACTIVE", "PAUSED", "ENDED")) {
            assertEquals(400, post("/admin/experiments", fields + ("status" to status)).statusCode())
        }
        assertEquals(0L, experiments.count())
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val id = experiments.findByKey("create-status")!!.id!!
        assertTrue(get("/admin/experiments/$id/edit").contains("value=\"SCHEDULED\""))
    }

    @Test
    fun `simulator displays nonparticipation for excluded users and closed periods without logging exposure`() {
        val zero = service.createExperiment(ExperimentCreateDto("zero", "", "purchase",
            listOf(VariantDto("A", 100)), trafficAllocation = 0))
        val expired = service.createExperiment(ExperimentCreateDto("expired", "", "purchase",
            listOf(VariantDto("A", 100)), endsAt = java.time.LocalDateTime.of(2020, 1, 1, 0, 0)))
        val included = service.createExperiment(ExperimentCreateDto("included", "", "purchase", listOf(VariantDto("A", 100))))
        assertFalse(get("/admin/simulator").contains("<strong>미참여</strong>"))
        for (experiment in listOf(zero, expired)) {
            val response = post("/admin/simulator/test", mapOf("experimentId" to experiment.id.toString(), "userId" to "u"))
            assertEquals(200, response.statusCode(), response.body())
            assertTrue(response.body().contains("<strong>미참여</strong>"))
            assertFalse(response.body().contains("배정 완료!"))
        }
        val assigned = post("/admin/simulator/test", mapOf("experimentId" to included.id.toString(), "userId" to "u"))
        assertEquals(200, assigned.statusCode(), assigned.body())
        assertTrue(assigned.body().contains("배정 완료!"))
        assertFalse(assigned.body().contains("<strong>미참여</strong>"))
        assertEquals(0L, impressions.count())
        assertEquals(0L, conversions.count())
    }

    @Test
    fun `concurrent scheduler ticks start once skip missed windows and respect paused reservations`() {
        val now = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).withNano(0)
        fun scheduled(key: String, start: java.time.LocalDateTime, end: java.time.LocalDateTime) =
            experiments.save(ExperimentEntity(key = key, description = "", goalEventName = "purchase",
                status = ExperimentStatus.SCHEDULED, configurationLocked = true, startsAt = start, endsAt = end).apply {
                addVariant(VariantEntity(name = "A", weight = 100))
            })
        val due = scheduled("due", now.minusMinutes(1), now.plusHours(1))
        val missed = scheduled("missed", now.minusHours(2), now.minusHours(1))
        val paused = scheduled("paused-reservation", now.minusMinutes(1), now.plusHours(1))
        service.pauseExperiment(paused.id!!)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        try {
            val tasks = (1..2).map {
                workers.submit {
                    start.await()
                    io.github.silbaram.prism.admin.service.ExperimentScheduler(experiments, service, true).tick()
                }
            }
            start.countDown()
            tasks.forEach { it.get(10, TimeUnit.SECONDS) }
        } finally { workers.shutdownNow() }
        assertEquals(ExperimentStatus.ACTIVE, experiments.findById(due.id!!).orElseThrow().status)
        assertEquals(ExperimentStatus.ENDED, experiments.findById(missed.id!!).orElseThrow().status)
        assertEquals(ExperimentStatus.PAUSED, experiments.findById(paused.id!!).orElseThrow().status)
        for (experiment in listOf(due, missed)) {
            val history = changes.findTop50ByExperimentIdOrderByIdDesc(experiment.id!!)
            assertEquals(1, history.size)
            assertEquals("SCHEDULE", history.single().action)
            assertEquals("system:scheduler", history.single().actor)
        }
    }

    @Test
    fun `scheduled experiments activate and end once and include named guardrails in the audit`() {
        val start = java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).plusHours(1).withNano(0)
        val end = start.plusHours(1)
        val fields = mapOf("key" to "scheduled", "description" to "", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100", "trafficAllocation" to "5",
            "startsAt" to start.toString(), "endsAt" to end.toString(), "guardrailEvents" to "payment_failed\ncrash")
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val id = experiments.findByKey("scheduled")!!.id!!
        assertEquals(400, post("/admin/experiments/$id", fields + ("status" to "ACTIVE")).statusCode())
        assertEquals(302, post("/admin/experiments/$id", fields + ("status" to "SCHEDULED")).statusCode())
        assertFalse(experiments.findScheduleCandidates(start.minusSeconds(1)).contains(id))
        assertTrue(experiments.findScheduleCandidates(start).contains(id))
        service.advanceSchedule(id, start.minusSeconds(1))
        assertEquals(ExperimentStatus.SCHEDULED, experiments.findById(id).orElseThrow().status)
        service.advanceSchedule(id, start)
        service.advanceSchedule(id, start)
        assertEquals(ExperimentStatus.ACTIVE, experiments.findById(id).orElseThrow().status)
        service.pauseExperiment(id)
        service.advanceSchedule(id, end)
        service.advanceSchedule(id, end)
        assertEquals(ExperimentStatus.ENDED, experiments.findById(id).orElseThrow().status)
        val history = changes.findTop50ByExperimentIdOrderByIdDesc(id)
        assertEquals(2, history.count { it.action == "SCHEDULE" })
        assertTrue(history.filter { it.action == "SCHEDULE" }.all { it.actor == "system:scheduler" })
        assertEquals("admin", history.last().actor)
        assertTrue(get("/admin/experiments/$id/edit").contains("payment_failed\ncrash") ||
            get("/admin/experiments/$id/edit").contains("crash\npayment_failed"))
    }

    @Test
    fun `participation may expand while metric definitions remain fixed and invalid input is rejected`() {
        val fields = mapOf("key" to "operations", "description" to "", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100", "trafficAllocation" to "5",
            "guardrailEvents" to "payment_failed")
        assertEquals(400, post("/admin/experiments", fields + ("trafficAllocation" to "101")).statusCode())
        assertEquals(400, post("/admin/experiments", fields + ("guardrailEvents" to "purchase")).statusCode())
        assertEquals(400, post("/admin/experiments", fields + ("targetingRules[0].expression" to "age >")).statusCode())
        assertEquals(400, post("/admin/experiments", fields + ("startsAt" to "not-a-date")).statusCode())
        assertEquals(400, post("/admin/experiments", fields + ("description" to "x".repeat(256))).statusCode())
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val id = experiments.findByKey("operations")!!.id!!
        val active = fields + ("status" to "ACTIVE")
        assertEquals(302, post("/admin/experiments/$id", active).statusCode())
        assertEquals(302, post("/admin/experiments/$id", active + ("trafficAllocation" to "25")).statusCode())
        assertEquals(400, post("/admin/experiments/$id", active).statusCode())
        assertEquals(400, post("/admin/experiments/$id", active + mapOf("trafficAllocation" to "25", "guardrailEvents" to "other")).statusCode())
        impressions.save(ImpressionLogEntity(experimentKey = "operations", variant = "A", userId = "u"))
        conversions.save(ConversionLogEntity(experimentKey = "operations", variant = "A", userId = "u", eventName = "payment_failed"))
        assertTrue(get("/admin/experiments/$id/events").contains("가드레일"))
        val missing = http.send(HttpRequest.newBuilder(uri("/admin/experiments/999999999")).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(404, missing.statusCode())
    }

    @Test
    fun `login CSRF viewer authorization and logout are enforced over HTTP`() {
        val anonymous = HttpClient.newBuilder().cookieHandler(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build()
        fun request(path: String) = anonymous.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(302, request("/admin/experiments").statusCode())
        assertEquals(403, postRaw(anonymous, "/admin/experiments", emptyMap()).statusCode())
        val token = csrf(request("/login").body())
        val failed = postRaw(anonymous, "/login", mapOf("username" to "viewer", "password" to "wrong", "_csrf" to token))
        assertTrue(failed.headers().firstValue("Location").orElse("").contains("error"))
        val login = postRaw(anonymous, "/login", mapOf("username" to "viewer", "password" to "prism-test-password", "_csrf" to csrf(request("/login").body())))
        assertEquals(302, login.statusCode())
        val list = request("/admin/experiments")
        assertEquals(200, list.statusCode())
        assertFalse(list.body().contains("새 실험 만들기"))
        assertEquals(403, request("/admin/experiments/new").statusCode())
        assertEquals(403, postRaw(anonymous, "/admin/experiments", mapOf("_csrf" to csrf(list.body()))).statusCode())
        assertEquals(403, postRaw(http, "/admin/experiments", emptyMap()).statusCode())
        assertEquals(302, postRaw(anonymous, "/logout", mapOf("_csrf" to csrf(list.body()))).statusCode())
        assertEquals(302, request("/admin/experiments").statusCode())
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
