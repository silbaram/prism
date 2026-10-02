package io.github.silbaram.prism.admin

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import io.github.silbaram.prism.admin.service.AnalyticsService
import io.github.silbaram.prism.admin.service.EventCatalogService
import io.github.silbaram.prism.admin.service.FunnelAnalysisService
import io.github.silbaram.prism.admin.service.FunnelQuery
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
    "spring.jpa.properties.hibernate.show_sql=false", "server.forward-headers-strategy=framework"
])
class AdminMetricsIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var environment: Environment
    @Autowired lateinit var changes: ExperimentChangeRepository
    @Autowired lateinit var service: ExperimentService
    @Autowired lateinit var funnels: FunnelAnalysisService
    @Autowired lateinit var analytics: AnalyticsService
    @Autowired lateinit var catalog: EventCatalogService
    @Autowired lateinit var definitions: EventDefinitionRepository
    @Autowired lateinit var transactions: PlatformTransactionManager
    @Autowired lateinit var dataSource: javax.sql.DataSource
    @Autowired lateinit var policies: PopulationPolicyRepository
    @Autowired lateinit var layers: ExperimentLayerRepository
    @Autowired lateinit var populationExposures: PopulationExposureRepository
    @Autowired lateinit var populationConversions: PopulationConversionRepository
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
        definitions.deleteAll()
        conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll(); changes.deleteAll()
        populationConversions.deleteAll(); populationExposures.deleteAll(); layers.deleteAll()
        policies.save(PopulationPolicyEntity())
        val loginPage = http.send(HttpRequest.newBuilder(uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString())
        val login = postRaw(http, "/login", mapOf("username" to "admin", "password" to "prism-test-password", "_csrf" to csrf(loginPage.body())))
        assertEquals(302, login.statusCode(), login.body())
        assertFalse(login.headers().firstValue("Location").orElse("").contains("error"))
    }

    @Test
    fun `experiment list renders its content for both empty and populated databases`() {
        val empty = get("/admin/experiments")
        assertTrue(empty.contains("<h2>실험 목록</h2>"))
        assertTrue(empty.contains("실험 Key 검색"))
        assertTrue(empty.contains("새 실험 만들기"))
        assertTrue(empty.contains("생성된 실험이 없습니다."))
        funnelExperiment("preview-render-check")
        val populated = get("/admin/experiments")
        assertTrue(populated.contains("<h2>실험 목록</h2>"))
        assertTrue(populated.contains("preview-render-check"))
        assertTrue(populated.contains("상세/통계"))
        assertFalse(populated.contains("생성된 실험이 없습니다."))
    }

    @Test
    fun `catalog joins planned configured and attributed events without counting invalid logs`() {
        catalog.register("planned", "before implementation")
        val experiment = experiments.save(ExperimentEntity(key = "catalog", description = "", goalEventName = "goal",
            guardrailEventNames = mutableSetOf("failure")))
        val other = funnelExperiment("catalog-other")
        funnelEvent(experiment, "u", "observed", funnelStart)
        funnelEvent(experiment, "u", "observed", funnelStart.plusHours(1))
        funnelEvent(other, "u", "observed", funnelStart.plusHours(2))
        conversions.save(ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "orphan",
            eventName = "invalid", timestamp = funnelStart))
        val items = catalog.catalog("").items.associateBy { it.name }
        assertEquals(setOf("planned", "goal", "failure", "purchase", "observed"), items.keys)
        assertTrue(items.getValue("planned").registered)
        assertEquals("before implementation", items.getValue("planned").description)
        assertEquals(0L, items.getValue("planned").events)
        assertNull(items.getValue("planned").lastOccurredAt)
        val observed = items.getValue("observed")
        assertFalse(observed.registered)
        assertEquals(3L, observed.events)
        assertEquals(2L, observed.experiments)
        assertEquals(funnelStart.plusHours(2), observed.lastOccurredAt)
        catalog.register("observed", "now documented")
        assertEquals(3L, catalog.catalog("observed").items.single().events)
        assertTrue(get("/admin/events").contains("now documented"))
    }

    @Test
    fun `catalog search treats wildcards literally preserves identities and bounds suggestions`() {
        listOf("Cart", "cart", "cart ", "a%b", "a_b", "a!b").forEach { catalog.register(it, "") }
        assertEquals(listOf("Cart", "cart", "cart "), catalog.suggestions("").names.filter { it.startsWith("cart", true) })
        for (literal in listOf("%", "_", "!")) {
            assertEquals(listOf("a${literal}b"), catalog.suggestions(literal).names)
        }
        assertEquals(listOf("cart "), catalog.suggestions("cart ").names)
        definitions.saveAll((0..104).map { EventDefinitionEntity(name = "bounded-${it.toString().padStart(3, '0')}") })
        val result = catalog.suggestions("bounded-")
        assertEquals(100, result.names.size)
        assertTrue(result.truncated)
        assertEquals("bounded-099", result.names.last())
        assertEquals(listOf("bounded-104"), catalog.suggestions("bounded-104").names)
        assertTrue(get("/admin/events/suggestions?q=cart").contains("cart "))
        assertTrue(get("/admin/events?q=bounded-").contains("최대 100개"))
    }

    @ParameterizedTest
    @ValueSource(strings = ["registered", "goal", "guardrail", "observed"])
    fun `exact catalog matches remain discoverable after the substring result limit`(source: String) {
        val exact = "purchase_%! "
        val names = (0..104).map { "a${it.toString().padStart(3, '0')}-$exact" } + exact
        when (source) {
            "registered" -> definitions.saveAll(names.map { EventDefinitionEntity(name = it, description = "registration") })
            "goal" -> experiments.saveAll(names.mapIndexed { index, name ->
                ExperimentEntity(key = "goal-$index", description = "", goalEventName = name)
            })
            "guardrail" -> experiments.saveAll(names.mapIndexed { index, name ->
                ExperimentEntity(key = "guardrail-$index", description = "", guardrailEventNames = mutableSetOf(name))
            })
            "observed" -> {
                val experiment = funnelExperiment()
                names.forEach { funnelEvent(experiment, "u", it, funnelStart) }
            }
        }
        val matches = catalog.suggestions(exact)
        assertTrue(matches.truncated)
        assertEquals(100, matches.names.size)
        assertEquals(exact, matches.names.first())
        assertEquals(exact, catalog.catalog(exact).items.first().name)
    }

    @Test
    fun `catalog registration redirects to the exact new event instead of the first hundred entries`() {
        definitions.saveAll((0..104).map { EventDefinitionEntity(name = "a-$it") })
        val name = "z-purchase 한글 +?&#/%! "
        val created = post("/admin/events", mapOf("name" to name))
        assertEquals(302, created.statusCode())
        val location = URI.create(created.headers().firstValue("Location").orElseThrow())
        assertNotNull(location.rawQuery)
        assertEquals("q=$name", java.net.URLDecoder.decode(location.rawQuery, StandardCharsets.UTF_8))
        assertTrue(get(location.rawPath + "?" + location.rawQuery).contains("z-purchase 한글"))
    }

    @Test
    fun `catalog navigation and suggestions retain the proxy context path`() {
        val experiment = funnelExperiment()
        for (path in listOf("/admin/events", "/admin/experiments/${experiment.id}/integration", "/admin/experiments/new")) {
            val response = http.send(HttpRequest.newBuilder(uri(path)).header("X-Forwarded-Prefix", "/prism").GET().build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), response.body())
            assertTrue(response.body().contains("href=\"/prism/admin/events\""), response.body())
            assertFalse(response.body().contains("href=\"/admin/events\""), response.body())
            if (path != "/admin/events") {
                assertTrue(response.body().contains("data-suggestions-url=\"/prism/admin/events/suggestions\""))
            }
        }
    }

    @Test
    fun `integration guide renders complete copyable snippets once`() {
        val experiment = funnelExperiment()
        val html = get("/admin/experiments/${experiment.id}/integration")
        val dependency = Regex("<code[^>]*>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).findAll(html)
            .map { org.springframework.web.util.HtmlUtils.htmlUnescape(it.groupValues[1]) }
            .first { it.startsWith("repositories") }
        assertEquals("repositories { mavenLocal(); mavenCentral() }\ndependencies {\n    implementation(\"io.github.silbaram.prism:prism-spring-boot-starter:0.0.1-SNAPSHOT\")\n}", dependency)
        assertEquals(1, Regex("id=\"javaCode\"").findAll(html).count())
        assertEquals(1, Regex("id=\"kotlinCode\"").findAll(html).count())
    }

    @Test
    fun `catalog registration validates input escapes HTML and requires admin and CSRF`() {
        val name = "</code><script>alert('event')</script>"
        assertEquals(302, post("/admin/events", mapOf("name" to name, "description" to "<b>description</b>")).statusCode())
        val html = get("/admin/events")
        assertFalse(html.contains(name))
        assertTrue(html.contains("&lt;script&gt;"))
        assertTrue(html.contains("&lt;b&gt;description&lt;/b&gt;"))
        for (fields in listOf(mapOf("name" to name), mapOf("name" to " "), mapOf("name" to "a\nb"),
            mapOf("name" to "x".repeat(256)), mapOf("name" to "valid", "description" to "x".repeat(1001)))) {
            val response = post("/admin/events", fields)
            assertEquals(400, response.statusCode(), response.body())
            assertFalse(response.body().contains("Exception"))
        }
        assertEquals(403, postRaw(http, "/admin/events", mapOf("name" to "no-csrf")).statusCode())
        assertEquals(1L, definitions.count())
        val experiment = funnelExperiment()
        HttpClient.newBuilder().cookieHandler(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build().use { viewer ->
            val login = viewer.send(HttpRequest.newBuilder(uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(302, postRaw(viewer, "/login", mapOf("username" to "viewer", "password" to "prism-test-password",
                "_csrf" to csrf(login.body()))).statusCode())
            for (path in listOf("/admin/events", "/admin/events/suggestions", "/admin/experiments/${experiment.id}/integration")) {
                val response = viewer.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode(), response.body())
                if (path == "/admin/events") {
                    assertFalse(response.body().contains("새 이벤트 등록"))
                    assertEquals(403, postRaw(viewer, path, mapOf("name" to "forbidden", "_csrf" to csrf(response.body()))).statusCode())
                }
            }
        }
        HttpClient.newHttpClient().use { anonymous ->
            for (path in listOf("/admin/events", "/admin/events/suggestions", "/admin/experiments/${experiment.id}/integration")) {
                assertEquals(302, anonymous.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode())
            }
        }
        assertEquals(1L, definitions.count())
    }

    @Test
    fun `event CVR uses unique exposed users includes zero rows and leaves goal metrics unchanged`() {
        val experiment = service.createExperiment(ExperimentCreateDto("event-cvr", "", "purchase",
            listOf(VariantDto("A", 50), VariantDto("B", 50), VariantDto("C", 0)), guardrailEventNames = setOf("failure")))
        funnelEvent(experiment, "u", "cart", funnelStart)
        funnelEvent(experiment, "u", "cart", funnelStart.plusHours(1))
        funnelEvent(experiment, "u", "purchase", funnelStart.plusHours(2))
        for (variant in listOf("A", "B")) impressions.save(ImpressionLogEntity(experimentKey = experiment.key,
            userId = "no-event", variant = variant, timestamp = funnelStart))
        conversions.save(ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "orphan",
            eventName = "cart", timestamp = funnelStart))
        val wrong = impressions.save(ImpressionLogEntity(experimentKey = "other", variant = "A", userId = "u", timestamp = funnelStart))
        conversions.save(ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "u",
            eventName = "cart", timestamp = funnelStart, impressionId = wrong.id))
        val rows = analytics.getEventStats(experiment.key, "cart").associateBy { it.variant }
        assertEquals(2L, rows.getValue("A").exposedUsers)
        assertEquals(1L, rows.getValue("A").users)
        assertEquals(2L, rows.getValue("A").events)
        assertEquals(50.0, rows.getValue("A").cvr)
        assertEquals(funnelStart.plusHours(1), rows.getValue("A").lastOccurredAt)
        assertEquals(0.0, rows.getValue("B").cvr)
        assertNull(rows.getValue("C").cvr)
        assertTrue(rows.values.all { it.kind == "SECONDARY" })
        assertTrue(analytics.getEventStats(experiment.key).filter { it.eventName == "failure" }.all { it.events == 0L && it.kind == "GUARDRAIL" })
        assertTrue(analytics.getEventStats(experiment.key, "not-sent").all { it.events == 0L })
        assertEquals(analytics.getExperimentStats(experiment.key).stats.single { it.variant == "A" }.cvr,
            analytics.getEventStats(experiment.key, "purchase").single { it.variant == "A" }.cvr)
        assertTrue(get("/admin/experiments/${experiment.id}/events?eventName=cart").contains("50.00%"))
        assertEquals("purchase", experiments.findByKey(experiment.key)!!.goalEventName)
    }

    @Test
    fun `event selections preserve exact names through create edit filter and SDK guide`() {
        val goal = " Purchase "
        val guardrail = "failure "
        val fields = mapOf("key" to "exact-names", "description" to "", "goalEventName" to goal,
            "guardrailEvents" to guardrail, "variants[0].name" to "A", "variants[0].weight" to "100")
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val experiment = service.getExperimentById(experiments.findByKey("exact-names")!!.id!!)
        assertEquals(goal, experiment.goalEventName)
        TransactionTemplate(transactions).executeWithoutResult {
            assertEquals(setOf(guardrail), service.getExperimentById(experiment.id!!).guardrailEventNames)
        }
        assertEquals(302, post("/admin/experiments/${experiment.id}", fields + ("description" to "updated")).statusCode())
        funnelEvent(experiment, "u", goal, funnelStart)
        funnelEvent(experiment, "u", "Purchase", funnelStart)
        assertEquals(1L, analytics.getEventStats(experiment.key, goal).single().events)
        assertEquals("PRIMARY", analytics.getEventStats(experiment.key, goal).single().kind)
        assertEquals("SECONDARY", analytics.getEventStats(experiment.key, "Purchase").single().kind)
        val active = fields + ("status" to "ACTIVE")
        assertEquals(302, post("/admin/experiments/${experiment.id}", active).statusCode())
        assertEquals(400, post("/admin/experiments/${experiment.id}", active + ("goalEventName" to goal.trim())).statusCode())
        assertEquals(400, post("/admin/experiments/${experiment.id}", active + ("guardrailEvents" to guardrail.trim())).statusCode())
        val guide = get("/admin/experiments/${experiment.id}/integration")
        assertTrue(guide.contains("&quot; Purchase &quot;"))
        assertTrue(guide.contains("prism-spring-boot-starter:0.0.1-SNAPSHOT"))
        assertTrue(guide.contains("\${PRISM_CLIENT_API_KEY}"))
        assertTrue(guide.contains("trackIfAssigned"))
        val dangerous = "</code><script>alert(\"x\")</script>\$value\\"
        val selected = get("/admin/experiments/${experiment.id}/integration?eventName=" + URLEncoder.encode(dangerous, StandardCharsets.UTF_8))
        assertFalse(selected.contains("<script>alert"))
        assertTrue(selected.contains("&lt;script&gt;"))
        assertEquals(goal, experiments.findByKey(experiment.key)!!.goalEventName)
    }

    @Test
    fun `catalog and guide reject invalid queries and handle an experiment without a goal`() {
        val legacy = experiments.save(ExperimentEntity(key = "no-goal", description = ""))
        val page = get("/admin/experiments/${legacy.id}/integration")
        assertTrue(page.contains("SDK 적용 가이드"))
        assertFalse(page.contains("id=\"javaCode\""))
        for (path in listOf("/admin/events?q=" + "x".repeat(256), "/admin/events/suggestions?q=" + "x".repeat(256),
            "/admin/experiments/${legacy.id}/integration?eventName=%20", "/admin/experiments/${legacy.id}/events?eventName=%20",
            "/admin/experiments/${legacy.id}/integration?eventName=" + "x".repeat(256))) {
            val response = http.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(400, response.statusCode(), response.body())
            assertFalse(response.body().contains("Exception"))
        }
        assertEquals(404, http.send(HttpRequest.newBuilder(uri("/admin/experiments/9223372036854775807/integration")).GET().build(),
            HttpResponse.BodyHandlers.ofString()).statusCode())
    }

    @Test
    fun `ordered funnel deduplicates users and ignores skipped and out of order stages`() {
        val experiment = funnelExperiment()
        fun event(user: String, name: String, hours: Long, variant: String = "A") =
            funnelEvent(experiment, user, name, funnelStart.plusHours(hours), variant)
        // Arrival order must not determine the funnel.
        event("complete", "purchase", 3); event("complete", "cart", 0)
        event("complete", "checkout", 1); event("complete", "cart", 2); event("complete", "checkout", 2)
        event("early", "checkout", 0); event("early", "cart", 1); event("early", "purchase", 2)
        event("boundary", "cart", 0); event("boundary", "checkout", 1); event("boundary", "purchase", 24)
        event("skip", "cart", 0); event("skip", "purchase", 1)
        event("complete-2", "cart", 0); event("complete-2", "checkout", 22); event("complete-2", "purchase", 23)
        event("other-variant", "cart", 0, "B")
        val report = funnels.report(experiment.id!!, FunnelQuery(listOf("cart", "checkout", "purchase"),
            funnelStart, funnelStart.plusDays(2), 24))
        val a = report.groups.single { it.variant == "A" }
        assertEquals(listOf(5L, 3L, 2L), a.stages.map { it.users })
        assertEquals(60.0, a.stages[1].fromPreviousPercent)
        assertEquals(40.0, a.stages[2].fromFirstPercent)
        assertEquals(200.0 / 3, a.stages[2].fromPreviousPercent!!, 1e-9)
        assertEquals(listOf(1L, 0L, 0L), report.groups.single { it.variant == "B" }.stages.map { it.users })
    }

    @Test
    fun `funnel excludes immature cohorts even if they already completed and never restarts first step`() {
        val experiment = funnelExperiment()
        funnelEvent(experiment, "mature", "cart", funnelStart)
        funnelEvent(experiment, "mature", "purchase", funnelStart.plusHours(1))
        funnelEvent(experiment, "pending", "cart", funnelStart.plusHours(25))
        funnelEvent(experiment, "pending", "purchase", funnelStart.plusHours(26))
        funnelEvent(experiment, "repeat", "cart", funnelStart)
        funnelEvent(experiment, "repeat", "cart", funnelStart.plusHours(20))
        funnelEvent(experiment, "repeat", "purchase", funnelStart.plusHours(25))
        val report = funnels.report(experiment.id!!, funnelQuery())
        val a = report.groups.single { it.variant == "A" }
        assertEquals(listOf(2L, 1L), a.stages.map { it.users })
        assertEquals(1L, a.pendingUsers)
        val empty = report.groups.single { it.variant == "B" }
        assertTrue(empty.stages.all { it.users == 0L && it.fromFirstPercent == null })
        assertNull(empty.stages[1].fromPreviousPercent)
    }

    @Test
    fun `funnel respects half open period boundaries strict timestamps and exposure identity`() {
        val experiment = funnelExperiment()
        funnelEvent(experiment, "at-start", "cart", funnelStart)
        funnelEvent(experiment, "at-start", "purchase", funnelStart.plusNanos(1000))
        funnelEvent(experiment, "same-time", "cart", funnelStart)
        funnelEvent(experiment, "same-time", "purchase", funnelStart)
        funnelEvent(experiment, "before", "cart", funnelStart.minusNanos(1000))
        funnelEvent(experiment, "before", "purchase", funnelStart.plusHours(1))
        funnelEvent(experiment, "at-end", "cart", funnelStart.plusDays(2))
        funnelEvent(experiment, "at-end", "purchase", funnelStart.plusDays(2).plusHours(1))
        conversions.save(ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "no-exposure",
            eventName = "cart", timestamp = funnelStart))
        val wrong = impressions.save(ImpressionLogEntity(experimentKey = experiment.key, variant = "B", userId = "wrong", timestamp = funnelStart))
        conversions.save(ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "wrong",
            eventName = "cart", timestamp = funnelStart, impressionId = wrong.id))
        val report = funnels.report(experiment.id!!, funnelQuery())
        assertEquals(listOf(2L, 1L), report.groups.single { it.variant == "A" }.stages.map { it.users })
    }

    @Test
    fun `funnel isolates experiments exact event names and user variant histories`() {
        val experiment = funnelExperiment()
        val other = funnelExperiment("other")
        funnelEvent(other, "other", "cart", funnelStart)
        funnelEvent(other, "other", "purchase", funnelStart.plusHours(1))
        funnelEvent(experiment, "shared", "cart", funnelStart, "A")
        funnelEvent(experiment, "shared", "purchase", funnelStart.plusHours(1), "B")
        funnelEvent(experiment, "case", "Cart", funnelStart)
        funnelEvent(experiment, "case", "purchase", funnelStart.plusHours(1))
        funnelEvent(experiment, "space", "cart ", funnelStart)
        val report = funnels.report(experiment.id!!, funnelQuery())
        assertEquals(listOf(1L, 0L), report.groups.single { it.variant == "A" }.stages.map { it.users })
        assertEquals(listOf(0L, 0L), report.groups.single { it.variant == "B" }.stages.map { it.users })
    }

    @Test
    fun `funnel keyset pages preserve a user spanning a page and include all later users`() {
        val experiment = funnelExperiment()
        val exposure = impressions.save(ImpressionLogEntity(experimentKey = experiment.key, variant = "A", userId = "000", timestamp = funnelStart))
        conversions.saveAll((0..1000).map {
            ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "000", eventName = "cart",
                timestamp = funnelStart, impressionId = exposure.id)
        } + ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = "000", eventName = "purchase",
            timestamp = funnelStart.plusHours(1), impressionId = exposure.id))
        for (i in 1..1002) {
            val user = "u-$i"
            val shown = impressions.save(ImpressionLogEntity(experimentKey = experiment.key, variant = "A", userId = user, timestamp = funnelStart))
            conversions.saveAll(listOf("cart", "purchase").mapIndexed { step, name ->
                ConversionLogEntity(experimentKey = experiment.key, variant = "A", userId = user, eventName = name,
                    timestamp = funnelStart.plusHours(step.toLong()), impressionId = shown.id)
            })
        }
        val report = funnels.report(experiment.id!!, funnelQuery())
        assertEquals(listOf(1003L, 1003L), report.groups.single { it.variant == "A" }.stages.map { it.users })
    }

    @Test
    fun `funnel is available over authenticated HTTP and renders ordered statistics`() {
        val experiment = funnelExperiment()
        funnelEvent(experiment, "u", "cart", funnelStart)
        funnelEvent(experiment, "u", "purchase", funnelStart.plusHours(1))
        assertTrue(get("/admin/experiments/${experiment.id}").contains("순서형 퍼널 보기"))
        assertTrue(get("/admin/experiments/${experiment.id}/funnel").contains("이벤트 순서"))
        val query = mapOf("steps" to "cart\npurchase", "from" to funnelStart.toString(),
            "until" to funnelStart.plusDays(2).toString(), "windowHours" to "24").entries.joinToString("&") {
            URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
        }
        val html = get("/admin/experiments/${experiment.id}/funnel?$query")
        assertTrue(html.contains("100.00%"))
        assertTrue(html.contains("관측 중 사용자"))
        assertFalse(html.contains("NaN"))
        val anonymous = HttpClient.newHttpClient()
        try {
            val response = anonymous.send(HttpRequest.newBuilder(uri("/admin/experiments/${experiment.id}/funnel?$query")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(302, response.statusCode())
            assertTrue(response.headers().firstValue("Location").orElseThrow().endsWith("/login"))
        } finally { anonymous.close() }
    }

    @Test
    fun `funnel rejects invalid step lists periods and windows over HTTP`() {
        val experiment = funnelExperiment()
        fun request(values: Map<String, String>): HttpResponse<String> {
            val query = (mapOf("steps" to "cart\npurchase", "from" to funnelStart.toString(),
                "until" to funnelStart.plusDays(2).toString(), "windowHours" to "24") + values).entries.joinToString("&") {
                URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
            }
            return http.send(HttpRequest.newBuilder(uri("/admin/experiments/${experiment.id}/funnel?$query")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
        }
        for (values in listOf(mapOf("steps" to ""), mapOf("steps" to "cart"), mapOf("steps" to "cart\ncart"),
            mapOf("steps" to (1..9).joinToString("\n") { "event-$it" }), mapOf("steps" to "x".repeat(256) + "\npurchase"),
            mapOf("from" to "invalid"), mapOf("from" to ""), mapOf("from" to funnelStart.plusDays(2).toString()),
            mapOf("from" to funnelStart.minusDays(366).toString()), mapOf("until" to "2999-01-01T00:00:00"),
            mapOf("from" to "-999999999-01-01T00:00:00", "until" to "-999999999-01-02T00:00:00"),
            mapOf("from" to funnelStart.plusNanos(1).toString()),
            mapOf("windowHours" to "0"), mapOf("windowHours" to "721"), mapOf("windowHours" to "invalid"))) {
            val response = request(values)
            assertEquals(400, response.statusCode(), values.toString() + response.body())
            assertFalse(response.body().contains("Exception"))
        }
    }

    @Test
    fun `viewer can read funnels and event names are escaped in the form and report`() {
        val experiment = funnelExperiment()
        val name = "</textarea><script>alert('event')</script>"
        funnelEvent(experiment, "u", name, funnelStart)
        funnelEvent(experiment, "u", "purchase", funnelStart.plusHours(1))
        val viewer = HttpClient.newBuilder().cookieHandler(java.net.CookieManager(null, java.net.CookiePolicy.ACCEPT_ALL)).build()
        try {
            val page = viewer.send(HttpRequest.newBuilder(uri("/login")).GET().build(), HttpResponse.BodyHandlers.ofString())
            assertEquals(302, postRaw(viewer, "/login", mapOf("username" to "viewer", "password" to "prism-test-password",
                "_csrf" to csrf(page.body()))).statusCode())
            val query = mapOf("steps" to "$name\npurchase", "from" to funnelStart.toString(),
                "until" to funnelStart.plusDays(2).toString(), "windowHours" to "24").entries.joinToString("&") {
                URLEncoder.encode(it.key, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(it.value, StandardCharsets.UTF_8)
            }
            val response = viewer.send(HttpRequest.newBuilder(uri("/admin/experiments/${experiment.id}/funnel?$query")).GET().build(),
                HttpResponse.BodyHandlers.ofString())
            assertEquals(200, response.statusCode(), response.body())
            assertTrue(response.body().contains("100.00%"))
            assertTrue(response.body().contains("&lt;script&gt;"))
            assertFalse(response.body().contains(name))
        } finally { viewer.close() }
    }

    private val funnelStart = java.time.LocalDateTime.of(2026, 1, 1, 0, 0)
    private fun funnelQuery() = FunnelQuery(listOf("cart", "purchase"), funnelStart, funnelStart.plusDays(2), 24)
    private fun funnelExperiment(key: String = "funnel"): ExperimentEntity {
        val experiment = ExperimentEntity(key = key, description = "", goalEventName = "purchase")
        experiment.addVariant(VariantEntity(name = "A", weight = 50))
        experiment.addVariant(VariantEntity(name = "B", weight = 50))
        return experiments.save(experiment)
    }
    private fun funnelEvent(experiment: ExperimentEntity, user: String, event: String,
                            at: java.time.LocalDateTime, variant: String = "A") {
        val exposure = impressions.save(ImpressionLogEntity(experimentKey = experiment.key, variant = variant, userId = user,
            timestamp = funnelStart.minusDays(1)))
        conversions.save(ConversionLogEntity(experimentKey = experiment.key, variant = variant, userId = user, eventName = event,
            timestamp = at, impressionId = exposure.id))
    }

    @Test
    fun `configuration changes on separate experiments commit in audit revision order`() {
        val first = service.createExperiment(ExperimentCreateDto("first", "", "purchase", listOf(VariantDto("A", 100))))
        val second = service.createExperiment(ExperimentCreateDto("second", "", "purchase", listOf(VariantDto("A", 100))))
        val recorded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val secondStarted = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val pending = pool.submit<Long> { TransactionTemplate(transactions).execute {
                service.startExperiment(first.id!!)
                val revision = changes.latestRevision()
                recorded.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                revision
            }!! }
            assertTrue(recorded.await(3, TimeUnit.SECONDS))
            val later = pool.submit {
                secondStarted.countDown()
                service.startExperiment(second.id!!)
            }
            assertTrue(secondStarted.await(1, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { later.get(200, TimeUnit.MILLISECONDS) }
            release.countDown()
            val firstRevision = pending.get(3, TimeUnit.SECONDS)
            later.get(3, TimeUnit.SECONDS)
            assertTrue(changes.latestRevision() > firstRevision)
            assertEquals(ExperimentStatus.ACTIVE, experiments.findById(first.id!!).orElseThrow().status)
            assertEquals(ExperimentStatus.ACTIVE, experiments.findById(second.id!!).orElseThrow().status)
        } finally { release.countDown(); pool.shutdownNow() }
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
    fun `layer ranges and holdout are validated locked audited and rendered`() {
        assertEquals(302, post("/admin/population/layers", mapOf("key" to "checkout", "description" to "Checkout tests")).statusCode())
        assertEquals(302, post("/admin/population/holdout", mapOf("key" to "global", "basisPoints" to "500")).statusCode())
        val lockedHoldout = post("/admin/population/holdout", mapOf("key" to "global", "basisPoints" to "600"))
        assertEquals(400, lockedHoldout.statusCode())
        assertTrue(lockedHoldout.body().contains("영구 홀드아웃은 설정 후 변경할 수 없습니다."), lockedHoldout.body())
        assertTrue(get("/admin/population").contains("고정됨"))
        assertTrue(get("/admin/population").contains("CREATE_LAYER"))
        val fields = mapOf("key" to "layer-a", "goalEventName" to "purchase", "variants[0].name" to "A",
            "variants[0].weight" to "100", "layerKey" to "checkout", "layerStart" to "0", "layerEnd" to "5000", "stickyBucketing" to "true")
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val id = experiments.findByKey("layer-a")!!.id!!
        assertEquals(400, post("/admin/experiments", fields + ("key" to "overlap")).statusCode())
        assertEquals(302, post("/admin/experiments", fields + mapOf("key" to "layer-b", "layerStart" to "5000", "layerEnd" to "10000")).statusCode())
        assertEquals(302, post("/admin/experiments/$id", fields + ("status" to "ACTIVE")).statusCode())
        val edit = get("/admin/experiments/$id/edit")
        assertTrue(edit.contains("value=\"checkout\""))
        assertTrue(edit.contains("name=\"stickyBucketing\" value=\"true\""))
        assertEquals(400, post("/admin/experiments/$id", fields + mapOf("status" to "ACTIVE", "layerEnd" to "6000")).statusCode())
        assertEquals(302, post("/admin/experiments/$id", fields + ("status" to "ENDED")).statusCode())
        assertEquals(400, post("/admin/experiments", fields + ("key" to "reuse-ended")).statusCode())
        assertEquals(2, changes.findTop50ByExperimentIdOrderByIdDesc(0).size)
        assertTrue(changes.findTop50ByExperimentIdOrderByIdDesc(0).all { it.actor == "admin" })
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
    fun `experiment editing preserves precise schedule values and rejected date input`() {
        val startsAt = "2030-01-01T00:00:00.123456"
        val endsAt = "2030-01-02T00:00:00.654321"
        val fields = mapOf("key" to "precise-schedule", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100", "startsAt" to startsAt, "endsAt" to endsAt)
        assertEquals(302, post("/admin/experiments", fields).statusCode())
        val id = experiments.findByKey("precise-schedule")!!.id!!
        fun dateInput(html: String, name: String) = Regex("<input[^>]+name=\"$name\"[^>]*>").find(html)!!.value
        for (status in listOf("DRAFT", "SCHEDULED")) {
            assertEquals(302, post("/admin/experiments/$id", fields + mapOf("status" to status, "description" to "changed")).statusCode())
            val page = get("/admin/experiments/$id/edit")
            for ((name, value) in listOf("startsAt" to startsAt, "endsAt" to endsAt)) {
                val input = dateInput(page, name)
                assertTrue(input.contains("type=\"text\""), input)
                assertTrue(input.contains("value=\"$value\""), input)
                if (status == "SCHEDULED") assertTrue(input.contains("readonly"), input)
            }
            val saved = service.getExperimentById(id)
            assertTrue(java.time.LocalDateTime.parse(startsAt).isEqual(saved.startsAt))
            assertTrue(java.time.LocalDateTime.parse(endsAt).isEqual(saved.endsAt))
        }
        val rejected = post("/admin/experiments/$id", fields + ("startsAt" to "not-a-date"))
        assertEquals(400, rejected.statusCode())
        val input = dateInput(rejected.body(), "startsAt")
        assertTrue(input.contains("type=\"text\"") && input.contains("value=\"not-a-date\""), input)
        for (raw in listOf("2030-01-01T00:00:00.123000", "2030-01-01T00:00:00.000000", "0000-01-01T00:00")) {
            val invalidForm = post("/admin/experiments", fields + mapOf("startsAt" to raw, "variants[0].weight" to "99"))
            assertEquals(400, invalidForm.statusCode())
            val date = dateInput(invalidForm.body(), "startsAt")
            assertTrue(date.contains("type=\"text\"") && date.contains("value=\"$raw\""), date)
        }
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
        assertEquals(200, request("/admin/population").statusCode())
        assertEquals(403, postRaw(anonymous, "/admin/population/holdout", mapOf("key" to "forbidden", "basisPoints" to "500", "_csrf" to csrf(list.body()))).statusCode())
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
