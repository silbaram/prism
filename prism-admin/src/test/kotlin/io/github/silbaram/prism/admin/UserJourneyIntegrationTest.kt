package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.service.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.*
import java.net.http.*
import java.time.LocalDateTime

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.admin.username=admin", "prism.schedule.enabled=false", "prism.analysis.finalization-enabled=false",
    "prism.admin.password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "prism.admin.viewer-username=viewer", "prism.admin.viewer-password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "spring.datasource.url=jdbc:h2:mem:journeys;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "spring.jpa.properties.hibernate.show_sql=false",
    "server.forward-headers-strategy=framework"
])
class UserJourneyIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var journeys: UserJourneyService
    @Autowired lateinit var funnels: FunnelAnalysisService
    @Autowired lateinit var environment: Environment
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    lateinit var journeyRepository: UserJourneyRepository
    private val start = LocalDateTime.of(2026, 1, 1, 0, 0)
    private lateinit var experiment: ExperimentEntity
    private val query get() = JourneyQuery(start, start.plusDays(2))
    private val funnel get() = FunnelQuery(listOf("cart", "checkout", "purchase"), start, start.plusDays(2), 24)

    @BeforeEach fun clean() {
        conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll()
        experiment = ExperimentEntity(key = "journey", description = "", goalEventName = "purchase")
        experiment.addVariant(VariantEntity(name = "A", weight = 50))
        experiment.addVariant(VariantEntity(name = "B", weight = 50))
        experiment = experiments.saveAndFlush(experiment)
    }
    private fun expose(user: String, variant: String = "A", at: LocalDateTime = start.minusHours(1), key: String = experiment.key) =
        impressions.saveAndFlush(ImpressionLogEntity(experimentKey = key, variant = variant, userId = user, timestamp = at))
    private fun event(user: String, name: String, at: LocalDateTime, shown: ImpressionLogEntity?, variant: String = "A", key: String = experiment.key) =
        conversions.saveAndFlush(ConversionLogEntity(experimentKey = key, variant = variant, userId = user,
            eventName = name, timestamp = at, impressionId = shown?.id))

    @Test fun `user summaries preserve exact identities periods variants and valid goal attribution`() {
        val a = expose("u")
        event("u", "purchase", start, a)
        event("u", "purchase", start.plusHours(1), a)
        event("u", "checkout", start.plusHours(2), a)
        event("u", "outside", query.until, a)
        val b = expose("u", "B")
        event("u", "cart", start, b, "B")
        event("U", "purchase", start, expose("U"))
        event("u ", "Purchase", start, expose("u "))
        event("orphan", "purchase", start, null)
        expose("only-exposure", at = start)
        val other = expose("u", key = "other")
        event("u", "purchase", start, other, key = "other")
        val users = journeys.users(experiment.id!!, query).items
        assertEquals(6, users.size)
        val row = users.single { it.userId == "u" && it.variant == "A" }
        assertEquals(3, row.events); assertEquals(2, row.goals); assertEquals(0, row.exposures)
        assertEquals(start, row.firstAt); assertEquals(start.plusHours(2), row.lastAt)
        assertEquals(1, users.single { it.userId == "orphan" }.excluded)
        assertEquals(setOf("u", "U"), journeys.users(experiment.id!!, query.copy(goalState = "REACHED")).items.map { it.userId }.toSet())
        assertEquals(4, journeys.users(experiment.id!!, query.copy(goalState = "NOT_REACHED")).items.size)
        assertEquals(listOf("u "), journeys.users(experiment.id!!, query.copy(userId = "u ")).items.map { it.userId })
        val paged = mutableListOf<Pair<String, String>>()
        var after: JourneyUserRow? = null
        do {
            val page = journeys.users(experiment.id!!, query.copy(size = 1), after?.userId, after?.variant)
            paged += page.items.map { it.userId to it.variant }; after = page.next
        } while (after != null)
        assertEquals(users.map { it.userId to it.variant }, paged)
    }

    @Test fun `timeline keeps retries repeated actions ties legacy attribution and invalid diagnostic rows across pages`() {
        val shown = expose("u", at = start.plusMinutes(1))
        event("u", "clock-skew", start, shown)
        event("u", "checkout", shown.timestamp, shown)
        event("u", "checkout", shown.timestamp, shown)
        event("u", "legacy", start.plusMinutes(2), null)
        event("u", "purchase", start.plusMinutes(3), expose("different"))
        event("u", "purchase", start.plusMinutes(4), shown)
        event("u", "excluded-end", query.until, shown)
        event("u", "other-variant", start, expose("u", "B"), "B")
        val detail = query.copy(userId = "u", variant = "A", size = 2)
        val entries = mutableListOf<JourneyEntry>()
        var cursor: JourneyCursor? = null
        do {
            val result = journeys.timeline(experiment.id!!, detail, cursor)
            assertEquals(6, result.summary!!.events)
            assertEquals(1, result.summary!!.goals)
            entries += result.entries; cursor = result.next
        } while (cursor != null)
        assertEquals(7, entries.size)
        assertEquals(7, entries.map { it.log.kind to it.log.id }.distinct().size)
        assertEquals(listOf("clock-skew", null, "checkout", "checkout", "legacy", "purchase", "purchase"), entries.map { it.log.eventName })
        assertTrue(entries.first().log.clockSkew)
        assertEquals(1, entries.first().log.attribution)
        assertTrue(entries[2].simultaneous); assertTrue(entries[3].simultaneous)
        assertEquals("0.000000초", entries[2].elapsed)
        assertEquals(2, entries[4].log.attribution)
        assertEquals(0, entries[5].log.attribution)
        assertThrows(AdminValidationException::class.java) {
            journeys.timeline(experiment.id!!, detail.copy(userId = "other"), JourneyCursor(shown.timestamp, 0, shown.id!!))
        }
    }

    @Test fun `funnel reached missing and pending users partition the same aggregate including page boundaries`() {
        fun path(user: String, hours: Long, names: List<String>, variant: String = "A") {
            val shown = expose(user, variant)
            names.reversed().forEachIndexed { index, name -> event(user, name, start.plusHours(hours + names.size - index - 1), shown, variant) }
        }
        path("complete", 0, funnel.steps)
        path("complete ", 0, funnel.steps)
        path("drop-one", 0, listOf("cart"))
        path("drop-two", 0, listOf("cart", "checkout"))
        path("pending", 25, funnel.steps)
        path("complete", 0, listOf("cart"), "B")
        val same = expose("same-time")
        funnel.steps.forEach { event("same-time", it, start, same) }
        val edge = expose("window-edge")
        event("window-edge", "cart", start, edge)
        event("window-edge", "checkout", start.plusHours(1), edge)
        event("window-edge", "purchase", start.plusHours(24), edge)
        val report = funnels.report(experiment.id!!, funnel)
        for (group in report.groups) {
            group.stages.forEachIndexed { index, stage ->
                val rows = mutableListOf<FunnelUser>()
                var after: String? = null
                do {
                    val page = funnels.users(experiment.id!!, funnel, group.variant, index + 1, FunnelSelection.REACHED, after, 1)
                    rows += page.items; after = page.nextUser
                } while (after != null)
                assertEquals(stage.users, rows.size.toLong())
                rows.forEach { assertEquals(it, funnels.user(experiment.id!!, funnel, it.userId, group.variant)) }
                if (index > 0) assertEquals(group.stages[index - 1].users - stage.users,
                    funnels.users(experiment.id!!, funnel, group.variant, index + 1, FunnelSelection.MISSING).items.size.toLong())
            }
            assertEquals(group.pendingUsers, funnels.users(experiment.id!!, funnel, group.variant, 1, FunnelSelection.PENDING).items.size.toLong())
        }
        assertEquals(listOf("pending"), funnels.users(experiment.id!!, funnel, "A", 1, FunnelSelection.PENDING).items.map { it.userId })
        assertEquals(setOf("drop-two", "window-edge"), funnels.users(experiment.id!!, funnel, "A", 3, FunnelSelection.MISSING).items.map { it.userId }.toSet())
    }

    @Test fun `legacy goal and empty histories remain explicitly unmeasured`() {
        experiment.goalEventName = null
        experiments.saveAndFlush(experiment)
        expose("u", at = start)
        assertEquals(0, journeys.users(experiment.id!!, query).items.single().goals)
        assertThrows(AdminValidationException::class.java) { journeys.users(experiment.id!!, query.copy(goalState = "REACHED")) }
        assertNull(journeys.timeline(experiment.id!!, query.copy(userId = "unknown", variant = "A")).summary)
        loggedIn("admin").use { client ->
            val page = get(client, "/admin/experiments/${experiment.id}/journeys?" +
                params(mapOf("from" to query.from.toString(), "until" to query.until.toString())))
            assertEquals(200, page.statusCode())
            val goalFilter = Regex("<select id=\"goalState\"[^>]+>").find(page.body())!!.value
            assertTrue(goalFilter.contains("disabled"), "Do not offer a goal filter that always returns HTTP 400")
            assertTrue(page.body().contains("목표 미설정"))
        }
    }

    @Test fun `funnel user pages finish histories spanning storage pages before returning a cursor`() {
        val first = expose("Case ")
        conversions.saveAllAndFlush((0..1004).map { ConversionLogEntity(experimentKey = experiment.key,
            userId = first.userId, variant = "A", eventName = "cart", timestamp = start, impressionId = first.id) })
        event(first.userId, "checkout", start.plusSeconds(1), first)
        event(first.userId, "purchase", start.plusSeconds(2), first)
        for (user in listOf("case", "한글", "\uE000", "\uD83D\uDE00")) {
            val shown = expose(user)
            funnel.steps.forEachIndexed { i, name -> event(user, name, start.plusSeconds(i.toLong()), shown) }
        }
        val all = funnels.users(experiment.id!!, funnel, "A", 3, FunnelSelection.REACHED).items
        val paged = mutableListOf<FunnelUser>()
        var cursor: String? = null
        do {
            val page = funnels.users(experiment.id!!, funnel, "A", 3, FunnelSelection.REACHED, cursor, 1)
            paged += page.items; cursor = page.nextUser
        } while (cursor != null)
        assertEquals(5, all.size)
        assertEquals(all, paged)
        assertEquals(3, funnels.user(experiment.id!!, funnel, first.userId, "A")!!.reached)
    }

    @Test fun `existing SDK sends a retry journey through the real API into the admin timeline`() {
        experiment.status = ExperimentStatus.ACTIVE
        experiments.saveAndFlush(experiment)
        // Transient API and Admin share only this test's in-memory database.
        org.springframework.boot.builder.SpringApplicationBuilder(io.github.silbaram.prism.api.PrismApiApplication::class.java).run(
            "--server.port=0", "--prism.api.keys=prism-test-api-key-0123456789abcdef",
            "--spring.datasource.url=${environment.getProperty("spring.datasource.url")}",
            "--spring.datasource.driver-class-name=org.h2.Driver", "--spring.datasource.username=sa", "--spring.datasource.password=",
            "--spring.jpa.hibernate.ddl-auto=validate", "--spring.sql.init.mode=never", "--spring.jpa.properties.hibernate.show_sql=false"
        ).use { api ->
            val from = LocalDateTime.now(java.time.ZoneOffset.UTC).minusMinutes(1).withNano(0)
            val names = listOf("cart", "checkout", "payment_failed", "checkout", "purchase")
            var variant: String
            io.github.silbaram.prism.sdk.PrismClient("http://127.0.0.1:${api.environment.getProperty("local.server.port")}",
                options = io.github.silbaram.prism.sdk.PrismClientOptions(apiKey = "prism-test-api-key-0123456789abcdef",
                    eventFlushInterval = java.time.Duration.ofMinutes(1))).use { sdk ->
                val assignment = sdk.assign("sdk-user", experiment.key)
                variant = requireNotNull(assignment.variant)
                names.forEach { assertTrue(sdk.trackConversion(assignment, it)) }
                assertTrue(sdk.flush())
            }
            val until = LocalDateTime.now(java.time.ZoneOffset.UTC).truncatedTo(java.time.temporal.ChronoUnit.MICROS)
            val result = journeys.timeline(experiment.id!!, JourneyQuery(from, until, "sdk-user", variant))
            assertEquals(1L, result.summary!!.exposures)
            assertEquals(5L, result.summary!!.events)
            assertEquals(1L, result.summary!!.goals)
            assertEquals(listOf(null) + names, result.entries.map { it.log.eventName })
            assertTrue(result.entries.all { it.log.attribution == 1 })
            loggedIn("admin").use { client ->
                val response = get(client, "/admin/experiments/${experiment.id}/journeys/user?" + params(mapOf(
                    "userId" to "sdk-user", "variant" to variant, "from" to from.toString(), "until" to until.toString())))
                assertEquals(200, response.statusCode(), response.body())
                assertTrue(response.body().contains("payment_failed")); assertTrue(response.body().contains("발생 확인"))
            }
        }
    }

    private fun params(values: Map<String, String>) = values.entries.joinToString("&") { (k, v) ->
        URLEncoder.encode(k, Charsets.UTF_8) + "=" + URLEncoder.encode(v, Charsets.UTF_8)
    }

    private fun href(html: String, text: String): String = Regex("href=\"([^\"]+)\">${Regex.escape(text)}</a>")
        .find(html)!!.groupValues[1].replace("&amp;", "&")

    @Test fun `journey page uses one snapshot for its timeline summary and funnel outcome during late ingestion`() {
        val shown = expose("snapshot")
        event("snapshot", "cart", start, shown)
        val rowsRead = java.util.concurrent.CountDownLatch(1)
        val committed = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        loggedIn("admin").use { client ->
            org.mockito.Mockito.doAnswer { call ->
                val result = call.callRealMethod()
                rowsRead.countDown()
                check(committed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                result
            }.`when`(journeyRepository).logs(experiment.key, "snapshot", "A", query.from, query.until, null, null, null, 51)
            try {
                val route = "/admin/experiments/${experiment.id}/journeys/user?" + params(mapOf(
                    "userId" to "snapshot", "variant" to "A", "from" to query.from.toString(), "until" to query.until.toString(),
                    "steps" to "cart\npurchase", "windowHours" to "24"))
                val response = pool.submit<HttpResponse<String>> { get(client, route) }
                assertTrue(rowsRead.await(5, java.util.concurrent.TimeUnit.SECONDS))
                event("snapshot", "purchase", start.plusHours(1), shown)
                committed.countDown()
                val page = response.get(5, java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(200, page.statusCode(), page.body())
                assertTrue(page.body().contains("이벤트 1회"))
                assertTrue(page.body().contains("도달 단계: 1/2"), "Timeline and funnel must use the same snapshot")
                assertFalse(page.body().contains("모든 단계 도달"))
                org.mockito.Mockito.reset(journeyRepository)
                assertTrue(get(client, route).body().contains("도달 단계: 2/2"))
            } finally { committed.countDown(); org.mockito.Mockito.reset(journeyRepository); pool.shutdownNow() }
        }
    }

    @Test fun `returning from timeline pages keeps the source user filter and cursor`() {
        for (user in listOf("a", "b")) {
            val shown = expose(user)
            event(user, "cart", start, shown); event(user, "purchase", start.plusHours(1), shown)
        }
        loggedIn("admin").use { client ->
            val base = "/admin/experiments/${experiment.id}"
            val list = "$base/journeys?" + params(mapOf("from" to query.from.toString(), "until" to query.until.toString(),
                "goalState" to "REACHED", "size" to "1", "afterUser" to "a", "afterVariant" to "A"))
            val detail = get(client, href(get(client, list).body(), "b"))
            val next = get(client, href(detail.body(), "다음 행동"))
            assertEquals(200, next.statusCode(), next.body())
            val back = href(next.body(), "사용자 여정 목록")
            assertEquals(list, back)
            assertEquals(200, get(client, back).statusCode())
            val funnelList = "$base/funnel/users?" + params(mapOf("from" to query.from.toString(), "until" to query.until.toString(),
                "steps" to "cart\npurchase", "variant" to "A", "stage" to "2", "selection" to "REACHED", "size" to "1", "afterUser" to "a"))
            val funnelDetail = get(client, href(get(client, funnelList).body(), "b"))
            val funnelNext = get(client, href(funnelDetail.body(), "다음 행동"))
            val backToFunnel = href(funnelNext.body(), "퍼널 사용자 목록")
            assertEquals(URI(funnelList).path, URI(backToFunnel).path)
            assertEquals(queryValues(funnelList) + ("windowHours" to "24"), queryValues(backToFunnel))
        }
    }

    @Test fun `date filters preserve microseconds and allow milliseconds in both analysis forms`() {
        loggedIn("admin").use { client ->
            for (route in listOf("journeys", "funnel")) {
                for ((fraction, type) in listOf("123456" to "text", "123" to "datetime-local")) {
                    val from = "2026-01-01T00:00:00.$fraction"
                    val until = "2026-01-03T00:00:00.$fraction"
                    val response = get(client, "/admin/experiments/${experiment.id}/$route?" +
                        params(mapOf("from" to from, "until" to until, "steps" to "cart\npurchase")))
                    assertEquals(200, response.statusCode(), response.body())
                    for ((name, value) in listOf("from" to from, "until" to until)) {
                        val input = Regex("<input id=\"$name\"[^>]+>").find(response.body())!!.value
                        assertTrue(input.contains("type=\"$type\""), input)
                        assertTrue(input.contains("value=\"$value\""), input)
                        assertTrue(input.contains("step=\"0.001\""), input)
                    }
                }
            }
        }
    }

    @Test fun `long unicode funnel names can open a user timeline and return through its pages`() {
        val steps = (1..8).map { "단".repeat(79) + it }
        val shown = expose("long-funnel")
        steps.forEachIndexed { i, name -> event("long-funnel", name, start.plusMinutes(i.toLong()), shown) }
        loggedIn("admin").use { client ->
            val route = "/admin/experiments/${experiment.id}/funnel/users?" + params(mapOf(
                "from" to query.from.toString(), "until" to query.until.toString(), "steps" to steps.joinToString("\n"),
                "variant" to "A", "stage" to "8", "selection" to "REACHED", "size" to "1"))
            val list = get(client, route)
            assertEquals(200, list.statusCode(), list.body())
            val detail = get(client, href(list.body(), "long-funnel"))
            assertEquals(200, detail.statusCode(), "Generated timeline URL must fit the default HTTP request limit")
            val next = get(client, href(detail.body(), "다음 행동"))
            assertEquals(200, next.statusCode(), next.body())
            assertEquals(200, get(client, href(next.body(), "퍼널 사용자 목록")).statusCode())
        }
    }

    @Test fun `funnel date inputs retain valid ISO spellings unsupported by native browser controls`() {
        loggedIn("admin").use { client ->
            val from = "2026-01-01t00:00:00"
            val until = "2026-01-03t00:00:00"
            val response = get(client, "/admin/experiments/${experiment.id}/funnel?" +
                params(mapOf("from" to from, "until" to until, "steps" to "cart\npurchase")))
            assertEquals(200, response.statusCode(), response.body())
            for ((name, value) in listOf("from" to from, "until" to until)) {
                val input = Regex("<input id=\"$name\"[^>]+>").find(response.body())!!.value
                assertTrue(input.contains("type=\"text\"") && input.contains("value=\"$value\""), input)
            }
        }
    }

    @Test fun `returning to the default user list retains the actual time boundaries`() {
        val at = LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(1).withNano(0)
        event("recent", "purchase", at, expose("recent", at = at.minusHours(1)))
        loggedIn("admin").use { client ->
            val list = get(client, "/admin/experiments/${experiment.id}/journeys")
            val detailLink = href(list.body(), "recent")
            val detail = get(client, detailLink)
            val back = href(detail.body(), "사용자 여정 목록")
            fun values(link: String) = URI(link).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
                it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
            }
            assertEquals(values(detailLink)["from"], values(back)["from"], "Keep the originally displayed default period")
            assertEquals(values(detailLink)["until"], values(back)["until"])
        }
    }
    private fun queryValues(route: String) = URI(route).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
        it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }

    @Test fun `timeline list return and page links work behind a proxy prefix for both reader roles`() {
        val shown = expose("proxy + ? & / 한글 ")
        funnel.steps.forEachIndexed { i, name -> event(shown.userId, name, start.plusHours(i.toLong()), shown) }
        for (role in listOf("admin", "viewer")) loggedIn(role).use { client ->
            fun throughProxy(route: String): String {
                assertTrue(route.startsWith("/prism/admin/experiments/"), route)
                val response = client.send(HttpRequest.newBuilder(uri(route.removePrefix("/prism")))
                    .header("X-Forwarded-Prefix", "/prism").GET().build(), HttpResponse.BodyHandlers.ofString())
                assertEquals(200, response.statusCode(), response.body())
                return response.body()
            }
            for (funnelList in listOf(false, true)) {
                val source = "/prism/admin/experiments/${experiment.id}/" + (if (funnelList) "funnel/users" else "journeys") + "?" +
                    params(mapOf("from" to query.from.toString(), "until" to query.until.toString(), "size" to "1") +
                        if (funnelList) mapOf("steps" to "cart\ncheckout\npurchase", "variant" to "A", "stage" to "3") else emptyMap())
                val list = throughProxy(source)
                val detailLink = Regex("href=\"([^\"]*/journeys/user[^\"]+)\"").find(list)!!.groupValues[1].replace("&amp;", "&")
                val detail = throughProxy(detailLink)
                val next = throughProxy(href(detail, "다음 행동"))
                val back = href(next, if (funnelList) "퍼널 사용자 목록" else "사용자 여정 목록")
                assertEquals(URI(source).path, URI(back).path)
                val original = queryValues(source)
                val restored = queryValues(back)
                original.forEach { (name, value) -> assertEquals(value, restored[name], name) }
                throughProxy(back)
            }
        }
    }

    @Test fun `malformed navigation and timeline inputs are client errors and cannot set an external return link`() {
        loggedIn("admin").use { client ->
            val route = "/admin/experiments/${experiment.id}/journeys/user?"
            val valid = mapOf("from" to query.from.toString(), "until" to query.until.toString(), "userId" to "u", "variant" to "A")
            for (bad in listOf(mapOf("listSource" to "https://example.com"), mapOf("listSize" to "bad"),
                mapOf("listSize" to "101"), mapOf("listFilterUser" to "bad"), mapOf("listGoalState" to "BAD"),
                mapOf("listSource" to "USERS", "listAfterUser" to "u"), mapOf("listSource" to "FUNNEL"),
                mapOf("listAfterUser" to " "), mapOf("afterKind" to "1"), mapOf("afterAt" to "bad", "afterKind" to "1", "afterId" to "1"))) {
                val response = get(client, route + params(valid + bad))
                assertEquals(400, response.statusCode(), bad.toString())
                assertFalse(response.body().contains("org.springframework"), response.body())
            }
            val response = get(client, route + params(valid + ("returnTo" to "https://example.com/")))
            assertEquals(200, response.statusCode())
            assertTrue(href(response.body(), "사용자 여정 목록").startsWith("/admin/experiments/${experiment.id}/journeys?"))
        }
    }

    private fun uri(route: String) = URI.create("http://127.0.0.1:${environment.getProperty("local.server.port")}$route")
    private fun get(client: HttpClient, route: String) = client.send(HttpRequest.newBuilder(uri(route)).GET().build(), HttpResponse.BodyHandlers.ofString())
    private fun loggedIn(user: String): HttpClient {
        val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
        val token = Regex("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").find(get(client, "/login").body())!!.groupValues[1]
        val response = client.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(params(mapOf("username" to user, "password" to "prism-test-password", "_csrf" to token)))).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(302, response.statusCode()); assertFalse(response.headers().firstValue("Location").orElse("").contains("error"))
        return client
    }

    @Test fun `authenticated pages escape identities preserve funnel context and validate bad requests`() {
        val user = "u & ? + / <script>alert(1)</script> "
        val shown = expose(user)
        funnel.steps.forEachIndexed { i, name -> event(user, name, start.plusHours(i.toLong()), shown) }
        event(user, "<img src=x onerror=alert(1)>", start.plusHours(4), shown)
        val base = "/admin/experiments/${experiment.id}"
        val period = mapOf("from" to query.from.toString(), "until" to query.until.toString())
        val funnelParams = period + mapOf("steps" to funnel.steps.joinToString("\n"), "windowHours" to "24", "variant" to "A", "stage" to "3", "selection" to "REACHED")
        HttpClient.newHttpClient().use { assertEquals(302, get(it, "$base/journeys").statusCode()) }
        for (role in listOf("admin", "viewer")) loggedIn(role).use { client ->
            for (route in listOf("$base/journeys?" + params(period), "$base/funnel?" + params(funnelParams),
                "$base/funnel/users?" + params(funnelParams), "$base/journeys/user?" + params(funnelParams + ("userId" to user)))) {
                val response = get(client, route)
                assertEquals(200, response.statusCode(), response.body())
                assertFalse(response.body().contains("<script>alert(1)</script>"))
                assertFalse(response.body().contains("<img src=x"))
            }
            val detail = get(client, "$base/journeys/user?" + params(funnelParams + ("userId" to user))).body()
            assertTrue(detail.contains("모든 단계 도달")); assertTrue(detail.contains("&lt;img"))
            assertTrue(detail.contains("퍼널 사용자 목록")); assertTrue(detail.contains("windowHours=24"))
            // Follow the actual generated link, including empty optional query values and encoded identity.
            val plain = get(client, "$base/journeys/user?" + params(period + mapOf("userId" to user, "variant" to "A", "size" to "1"))).body()
            val nextHref = Regex("href=\"([^\"]+)\">다음 행동</a>").find(plain)!!.groupValues[1].replace("&amp;", "&")
            val nextPage = get(client, nextHref)
            assertEquals(200, nextPage.statusCode(), nextPage.body())
            assertTrue(nextPage.body().contains("checkout")); assertFalse(nextPage.body().contains("id=\"funnelStatus\""))
            for (bad in listOf(mapOf("from" to "bad"), mapOf("size" to "0"), mapOf("size" to "101"),
                mapOf("until" to "2999-01-01T00:00:00"), mapOf("from" to query.from.plusNanos(1).toString()),
                mapOf("userId" to " "), mapOf("variant" to "x".repeat(256)), mapOf("goalState" to "BAD"), mapOf("afterUser" to "u"))) {
                assertEquals(400, get(client, "$base/journeys?" + params(period + bad)).statusCode(), bad.toString())
            }
            assertEquals(400, get(client, "$base/journeys/user?" + params(period + mapOf("userId" to user, "variant" to "A", "afterId" to "1"))).statusCode())
            assertEquals(400, get(client, "$base/funnel/users?" + params(funnelParams + mapOf("selection" to "MISSING", "stage" to "1"))).statusCode())
            assertEquals(404, get(client, "/admin/experiments/999999999/journeys?" + params(period)).statusCode())
        }
    }
}
