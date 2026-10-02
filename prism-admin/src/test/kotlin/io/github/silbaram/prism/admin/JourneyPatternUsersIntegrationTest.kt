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
import org.springframework.web.util.HtmlUtils
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDateTime
import java.util.Base64
import javax.sql.DataSource

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.admin.username=admin", "prism.schedule.enabled=false", "prism.analysis.finalization-enabled=false",
    "prism.admin.password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "prism.admin.viewer-username=viewer", "prism.admin.viewer-password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "spring.datasource.url=jdbc:h2:mem:patternusers;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "spring.jpa.properties.hibernate.show_sql=false",
    "server.forward-headers-strategy=framework"
])
class JourneyPatternUsersIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var patterns: JourneyPatternService
    @Autowired lateinit var dataSource: DataSource
    @Autowired lateinit var environment: Environment
    private lateinit var experiment: ExperimentEntity
    private val from = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123000)
    private val until = from.plusDays(2)
    private val query get() = JourneyPatternQuery(from, until, depth = 3, top = 50)
    private val base get() = "/admin/experiments/${experiment.id}/journeys/patterns"

    @BeforeEach fun clean() {
        conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll()
        experiment = ExperimentEntity(key = "pattern-users", description = "", goalEventName = "purchase").apply {
            addVariant(VariantEntity(name = "A", weight = 50)); addVariant(VariantEntity(name = "B", weight = 50))
        }.let(experiments::saveAndFlush)
    }
    private fun expose(user: String, variant: String = "A", at: LocalDateTime = from.minusHours(1), key: String = experiment.key) =
        impressions.saveAndFlush(ImpressionLogEntity(experimentKey = key, userId = user, variant = variant, timestamp = at))
    private fun event(shown: ImpressionLogEntity, name: String, second: Long = 0, at: LocalDateTime = from.plusSeconds(second),
        impressionId: Long? = shown.id) = conversions.saveAndFlush(ConversionLogEntity(experimentKey = shown.experimentKey,
        userId = shown.userId, variant = shown.variant, eventName = name, timestamp = at, impressionId = impressionId))
    private fun path(user: String, names: List<String>, variant: String = "A") = expose(user, variant).also { shown ->
        names.forEachIndexed { index, name -> event(shown, name, index.toLong()) }
    }

    @Test fun `every displayed pattern user page and goal partition matches aggregate including repeats ties and truncated prefixes`() {
        path("reached", listOf("cart", "checkout", "checkout", "purchase"))
        path("not-reached", listOf("cart", "checkout", "checkout", "failed"))
        path("complete-prefix", listOf("cart", "checkout", "checkout"))
        val tieOne = expose("tie-one"); event(tieOne, "z"); event(tieOne, "cart"); event(tieOne, "purchase", 1)
        val tieTwo = expose("tie-two"); event(tieTwo, "cart"); event(tieTwo, "z"); event(tieTwo, "purchase", 1)
        val excluded = expose("oversized-first-group"); repeat(4) { event(excluded, "cart") }; event(excluded, "purchase", 1)
        val partial = expose("truncated-before-tie"); event(partial, "cart")
        repeat(3) { event(partial, "checkout", 1) }; event(partial, "purchase", 2)
        path("reached", listOf("cart", "checkout", "checkout", "purchase"), "B")
        val report = patterns.report(experiment.id!!, query)
        assertEquals(1, report.groups.first { it.variant == "A" }.excludedUsers)
        for (group in report.groups) for (row in group.patterns) {
            val selected = query.copy(variant = group.variant)
            val collected = mutableListOf<String>()
            var after: String? = null
            do {
                val page = patterns.users(experiment.id!!, selected, row.path.key, size = 1, afterUser = after)
                assertEquals(row.users, page.totalUsers); assertEquals(row.goals, page.goalUsers)
                assertEquals(row.users, page.matchedUsers); assertEquals(row.path, page.path)
                collected += page.items.map { it.userId }; after = page.nextUser
            } while (after != null)
            assertEquals(row.users.toInt(), collected.size)
            assertEquals(collected.size, collected.distinct().size)
            val reached = patterns.users(experiment.id!!, selected, row.path.key, "REACHED")
            val missing = patterns.users(experiment.id!!, selected, row.path.key, "NOT_REACHED")
            assertEquals(row.goals, reached.matchedUsers); assertEquals(row.users - row.goals, missing.matchedUsers)
            assertTrue(reached.items.all { it.hasGoal }); assertTrue(missing.items.none { it.hasGoal })
            assertEquals(collected.toSet(), (reached.items + missing.items).map { it.userId }.toSet())
        }
        val a = report.groups.first { it.variant == "A" }
        val repeated = a.patterns.single { it.path.groups == listOf(listOf("cart"), listOf("checkout"), listOf("checkout")) && it.path.truncated }
        val members = patterns.users(experiment.id!!, query.copy(variant = "A"), repeated.path.key)
        assertEquals(setOf("reached", "not-reached"), members.items.map { it.userId }.toSet())
        assertTrue(members.items.all { it.events == 4L && it.firstAt.isEqual(from) && it.lastAt.isEqual(from.plusSeconds(3)) })
        assertNotEquals(repeated.path.key, a.patterns.single { !it.path.truncated && it.path.groups == repeated.path.groups }.path.key)
        assertEquals(2, a.patterns.single { it.path.groups == listOf(listOf("cart", "z"), listOf("purchase")) }.users)
    }

    @Test fun `pattern membership preserves exact identities attribution and half open period boundaries`() {
        for (user in listOf("u", "U", "u ", "u & ? + / <script>")) {
            val shown = expose(user)
            event(shown, "cart"); event(shown, "purchase", 1)
            event(shown, "outside", at = until); event(shown, "outside", at = from.minusNanos(1000))
        }
        val legacy = expose("legacy"); expose("legacy")
        event(legacy, "cart", impressionId = null); event(legacy, "purchase", 1, impressionId = null)
        val skew = expose("clock-skew", at = from.plusMinutes(1))
        event(skew, "cart"); event(skew, "purchase", 1)
        path("u", listOf("cart", "purchase"), "A ")
        path("u", listOf("cart", "purchase"), "B")
        val invalid = expose("invalid")
        event(invalid, "cart"); event(invalid, "purchase", 1, impressionId = expose("wrong-user").id)
        val orphan = ImpressionLogEntity(experimentKey = experiment.key, userId = "orphan", variant = "A", timestamp = from)
        event(orphan, "cart", impressionId = null); event(orphan, "purchase", 1, impressionId = null)
        val different = expose("u", key = "other-experiment"); event(different, "cart"); event(different, "purchase", 1)
        val selected = query.copy(variant = "A")
        val row = patterns.report(experiment.id!!, selected).groups.single().patterns.single { it.path.groups.size == 2 }
        val result = patterns.users(experiment.id!!, selected, row.path.key)
        assertEquals(setOf("u", "U", "u ", "u & ? + / <script>", "legacy", "clock-skew"), result.items.map { it.userId }.toSet())
        assertEquals(6, result.totalUsers); assertEquals(6L, result.goalUsers)
        assertTrue(result.items.all { it.events == 2L && it.variant == "A" })
        assertEquals(listOf("u"), patterns.users(experiment.id!!, selected.copy(variant = "A "), row.path.key).items.map { it.userId })
        assertEquals(listOf("u"), patterns.users(experiment.id!!, selected.copy(variant = "B"), row.path.key).items.map { it.userId })
    }

    @Test fun `scan completes histories across storage pages and user cursors use database ordering even for absent unicode cursor`() {
        val heavy = expose("00-heavy")
        conversions.saveAllAndFlush((0..1003).map { index -> ConversionLogEntity(experimentKey = experiment.key,
            userId = heavy.userId, variant = "A", eventName = when (index) { 0 -> "cart"; 1 -> "purchase"; else -> "noise" },
            timestamp = from.plusSeconds(index.toLong()), impressionId = heavy.id) })
        for (user in listOf("A", "a", "a ", "a&+? /", "ü", "한글", "\uE000", "😀")) {
            path(user, listOf("cart", "purchase", "noise"))
        }
        val selected = query.copy(variant = "A", depth = 2)
        val row = patterns.report(experiment.id!!, selected).groups.single().patterns.single()
        assertEquals(9, row.users)
        fun databaseIds(after: String?): List<String> = dataSource.connection.use { connection ->
            connection.prepareStatement("SELECT DISTINCT user_id FROM log_conversion WHERE experiment_key = ? AND variant = ?" +
                (if (after == null) "" else " AND user_id > ?") + " ORDER BY user_id").use { statement ->
                statement.setString(1, experiment.key); statement.setString(2, "A")
                after?.let { statement.setString(3, it) }
                statement.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getString(1)) } }
            }
        }
        for (initial in listOf(null, "0-missing", "a!", "\uD7FF", "😀!")) {
            val ids = mutableListOf<String>()
            var after = initial
            do {
                val page = patterns.users(experiment.id!!, selected, row.path.key, size = 1, afterUser = after)
                assertEquals(9, page.totalUsers); assertEquals(9, page.matchedUsers)
                ids += page.items.map { it.userId }; after = page.nextUser
            } while (after != null)
            assertEquals(databaseIds(initial), ids, "Cursor $initial must match the database collation")
        }
        val first = patterns.users(experiment.id!!, selected, row.path.key, size = 1).items.single()
        assertEquals(1004, first.events); assertEquals(from.plusSeconds(1003), first.lastAt)
    }

    @Test fun `actual aggregate links preserve filtered page context across timeline pagination and proxies for both reader roles`() {
        for ((index, user) in listOf("a", "b + & / <script>alert(1)</script>", "c", "d", "e").withIndex()) {
            path(user, listOf("cart", "checkout", if (index < 3) "purchase" else "failure"), "A ")
        }
        HttpClient.newHttpClient().use { assertEquals(302, get(it, base).statusCode()) }
        for (role in listOf("admin", "viewer")) loggedIn(role).use { client ->
            fun throughProxy(route: String): String {
                assertTrue(route.startsWith("/prism/admin/experiments/"), route)
                val response = get(client, route.removePrefix("/prism"), "/prism")
                assertEquals(200, response.statusCode(), response.body())
                return response.body()
            }
            for (allVariants in listOf(true, false)) {
                val reportValues = mapOf("from" to from.toString(), "until" to until.toString(), "depth" to "2", "top" to "3") +
                    if (allVariants) emptyMap() else mapOf("variant" to "A ")
                val report = throughProxy("/prism$base?" + params(reportValues))
                val countLink = countLink(report)
                val values = queryValues(countLink)
                assertEquals("A ", values["variant"]); assertEquals(allVariants.toString(), values["allVariants"])
                assertTrue(values.getValue("pathKey").matches(Regex("[0-9a-f]{64}")))
                val firstLink = withQuery(countLink, values + mapOf("goalState" to "REACHED", "size" to "1"))
                val first = throughProxy(firstLink)
                assertTrue(first.contains("id=\"patternUsers\""))
                val secondLink = href(first, "다음 사용자")
                val second = throughProxy(secondLink)
                val timelineLink = Regex("href=\"([^\"]*/journeys/user[^\"]+)\"").find(second)!!.groupValues[1].let(HtmlUtils::htmlUnescape)
                val timeline = throughProxy(withQuery(timelineLink, queryValues(timelineLink) + ("size" to "1")))
                assertFalse(timeline.contains("<script>alert(1)</script>"))
                val nextTimeline = throughProxy(href(timeline, "다음 행동"))
                val back = href(nextTimeline, "경로 사용자 목록")
                val restored = queryValues(back)
                val previous = queryValues(secondLink)
                for (field in listOf("from", "until", "variant", "goalState", "size", "afterUser", "allVariants", "depth", "top", "pathKey")) {
                    assertEquals(previous[field], restored[field], field)
                }
                throughProxy(back)
                val reportBack = Regex("href=\"([^\"]*/journeys/patterns\\?[^\"]+)\"").find(second)!!.groupValues[1].let(HtmlUtils::htmlUnescape)
                assertEquals(reportValues, queryValues(reportBack))
                throughProxy(reportBack)
            }
        }
    }

    @Test fun `unknown valid paths stay empty and malformed filters or goalless goal filters are client errors`() {
        path("u", listOf("cart", "purchase"))
        val selected = query.copy(variant = "A")
        val pathKey = patterns.report(experiment.id!!, selected).groups.single().patterns.single().path.key
        val unknown = patterns.users(experiment.id!!, selected, "0".repeat(64))
        assertTrue(unknown.items.isEmpty()); assertNull(unknown.path)
        assertEquals(0, unknown.totalUsers); assertEquals(0, unknown.matchedUsers); assertNull(unknown.nextUser)
        assertThrows(AdminValidationException::class.java) { patterns.users(experiment.id!!, query, pathKey) }
        val valid = mapOf("from" to from.toString(), "until" to until.toString(), "variant" to "A", "pathKey" to pathKey)
        loggedIn("admin").use { client ->
            assertEquals(200, get(client, "$base/users?" + params(valid + ("pathKey" to "0".repeat(64)))).statusCode())
            for (bad in listOf(mapOf("pathKey" to "bad"), mapOf("pathKey" to "A".repeat(64)), mapOf("pathKey" to "<script>"),
                mapOf("size" to "0"), mapOf("size" to "101"), mapOf("goalState" to "BAD"), mapOf("afterUser" to " "),
                mapOf("variant" to " "), mapOf("depth" to "1"), mapOf("top" to "51"), mapOf("from" to "bad"),
                mapOf("until" to "2999-01-01T00:00:00"), mapOf("allVariants" to "bad"))) {
                val response = get(client, "$base/users?" + params(valid + bad))
                assertEquals(400, response.statusCode(), bad.toString() + response.body())
                assertFalse(response.body().contains("org.springframework"))
            }
            assertEquals(400, get(client, "$base/users?" + params(valid - "variant")).statusCode())
            assertEquals(404, get(client, "/admin/experiments/999999999/journeys/patterns/users?" + params(valid)).statusCode())
            experiment.goalEventName = null; experiments.saveAndFlush(experiment)
            val noGoal = patterns.users(experiment.id!!, selected, pathKey)
            assertNull(noGoal.goalUsers); assertNull(noGoal.goalEventName)
            val noGoalHtml = ok(client, "$base/users?" + params(valid))
            val goalSelect = Regex("<select\\b[^>]*name=\"goalState\"[^>]*>").find(noGoalHtml)!!.value
            assertTrue(goalSelect.contains("disabled"), goalSelect)
            for (goal in listOf("REACHED", "NOT_REACHED")) {
                assertEquals(400, get(client, "$base/users?" + params(valid + ("goalState" to goal))).statusCode())
            }
        }
    }

    @Test fun `submitted goal filters preserve newline and unicode variants through user and timeline pagination`() {
        val variants = listOf("A\nB", "A\rB", "A\r\nB", "한글😀+%&")
        for ((index, variant) in variants.withIndex()) {
            repeat(index + 2) { path("u-$index-reached-$it", listOf("cart", "checkout", "purchase"), variant) }
            path("u-$index-missing", listOf("cart", "checkout", "failure"), variant)
        }
        loggedIn("admin").use { client ->
            for ((index, variant) in variants.withIndex()) {
                val reportValues = mapOf("from" to from.toString(), "until" to until.toString(),
                    "variant" to variant, "depth" to "2", "top" to "3")
                val countLink = countLink(ok(client, "$base?" + params(reportValues)))
                assertEquals(variant, queryValues(countLink)["variant"])
                val initial = ok(client, countLink)
                assertPatternCounts(initial, index + 3, index + 3, index + 2)
                val form = Regex("<form\\b[^>]*action=\"([^\"]*/journeys/patterns/users)\"[^>]*>([\\s\\S]*?)</form>")
                    .find(initial) ?: error("Missing pattern user filter form")
                val hidden = Regex("<input\\b[^>]*>").findAll(form.groupValues[2]).map { input ->
                    Regex("([A-Za-z][A-Za-z0-9:-]*)=\"([^\"]*)\"").findAll(input.value).associate {
                        it.groupValues[1] to HtmlUtils.htmlUnescape(it.groupValues[2])
                    }
                }.filter { it["type"] == "hidden" }.associate { it.getValue("name") to it.getValue("value") }
                assertFalse(hidden.containsKey("variant"), "Raw newlines cannot survive browser form submission unchanged")
                val token = hidden.getValue("variantToken")
                assertTrue(token.matches(Regex("[A-Za-z0-9_-]+")))
                assertEquals(variant, String(Base64.getUrlDecoder().decode(token), Charsets.UTF_8))
                val action = HtmlUtils.htmlUnescape(form.groupValues[1])
                val first = ok(client, action + "?" + params(hidden + mapOf("goalState" to "REACHED", "size" to "1")))
                assertPatternCounts(first, index + 3, index + 2, index + 2)
                val missing = ok(client, action + "?" + params(hidden + mapOf("goalState" to "NOT_REACHED", "size" to "1")))
                assertPatternCounts(missing, index + 3, 1, index + 2)
                assertTrue(missing.contains("u-$index-missing"))
                val secondLink = href(first, "다음 사용자")
                val pageValues = queryValues(secondLink)
                assertEquals(variant, pageValues["variant"])
                assertFalse(pageValues.containsKey("variantToken"), "Links keep the canonical raw variant parameter")
                val second = ok(client, secondLink)
                assertPatternCounts(second, index + 3, index + 2, index + 2)
                val timelineLink = Regex("href=\"([^\"]*/journeys/user[^\"]+)\"").find(second)!!.groupValues[1]
                    .let(HtmlUtils::htmlUnescape)
                assertEquals(variant, queryValues(timelineLink)["variant"])
                val timeline = ok(client, timelineLink)
                val nextTimelineLink = href(timeline, "다음 행동")
                assertEquals(variant, queryValues(nextTimelineLink)["variant"])
                val back = href(ok(client, nextTimelineLink), "경로 사용자 목록")
                assertEquals(pageValues, queryValues(back))
                assertPatternCounts(ok(client, back), index + 3, index + 2, index + 2)
                val reportBack = href(second, "경로 패턴으로 돌아가기")
                assertEquals(reportValues, queryValues(reportBack))
                ok(client, reportBack)
            }
        }
    }

    @Test fun `variant tokens reject malformed encodings invalid identities and conflicting raw variants`() {
        path("u", listOf("cart", "purchase"))
        val pathKey = patterns.report(experiment.id!!, query.copy(variant = "A")).groups.single().patterns.single().path.key
        val common = mapOf("from" to from.toString(), "until" to until.toString(), "pathKey" to pathKey)
        fun encoded(value: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))
        val invalidUtf8 = listOf(byteArrayOf(0xc3.toByte(), 0x28), byteArrayOf(0x80.toByte()),
            byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())).map { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        loggedIn("admin").use { client ->
            assertPatternCounts(ok(client, "$base/users?" + params(common + ("variantToken" to encoded("A")))), 1, 1, 1)
            for (token in listOf("", "A", "QQ==", "QQ+", "QQ/", "QQ\n", "QQ ", "한글", "A".repeat(1021),
                encoded(" \r\n"), encoded("x".repeat(256))) + invalidUtf8) {
                val response = get(client, "$base/users?" + params(common + ("variantToken" to token)))
                assertEquals(400, response.statusCode(), "Invalid token: $token\n${response.body()}")
                assertFalse(response.body().contains("org.springframework"))
            }
            for (raw in listOf("A", "B", "")) {
                val response = get(client, "$base/users?" + params(common + mapOf("variant" to raw, "variantToken" to encoded("A"))))
                assertEquals(400, response.statusCode(), response.body())
            }
            assertEquals(400, get(client, "$base/users?" + params(common)).statusCode())
            val longest = "한".repeat(255)
            path("longest", listOf("cart", "purchase"), longest)
            assertEquals(1020, encoded(longest).length)
            assertPatternCounts(ok(client, "$base/users?" + params(common + ("variantToken" to encoded(longest)))), 1, 1, 1)
        }
    }

    @Test fun `maximum length event names use compact structural keys in user and timeline links`() {
        val steps = (1..8).map { "단".repeat(254) + it }
        path("maximum", steps)
        loggedIn("admin").use { client ->
            val report = ok(client, "$base?" + params(mapOf("from" to from.toString(), "until" to until.toString(), "depth" to "8")))
            val link = countLink(report)
            assertTrue(link.length < 1500, "Links carry a structural key rather than all eight names")
            assertEquals(64, queryValues(link).getValue("pathKey").length)
            val users = ok(client, link)
            assertTrue(users.contains(steps.last()))
            val timelineLink = Regex("href=\"([^\"]*/journeys/user[^\"]+)\"").find(users)!!.groupValues[1].let(HtmlUtils::htmlUnescape)
            assertTrue(timelineLink.length < 2000)
            assertEquals(queryValues(link)["pathKey"], queryValues(timelineLink)["patternKey"])
            val timeline = ok(client, timelineLink)
            val back = href(timeline, "경로 사용자 목록")
            assertEquals(queryValues(link)["pathKey"], queryValues(back)["pathKey"])
            ok(client, back)
        }
    }

    private fun countLink(html: String): String {
        val cell = Regex("<td\\b[^>]*class=\"[^\"]*pattern-users[^\"]*\"[^>]*>([\\s\\S]*?)</td>").find(html)!!.groupValues[1]
        return Regex("href=\"([^\"]+)\"").find(cell)!!.groupValues[1].let(HtmlUtils::htmlUnescape)
    }
    private fun assertPatternCounts(html: String, total: Int, matched: Int, goals: Int) {
        val summary = Regex("<div\\b[^>]*id=\"patternUsersSummary\"[^>]*>([\\s\\S]*?)</div>")
            .find(html)?.groupValues?.get(1) ?: error("Missing pattern user summary")
        for ((label, count) in listOf("이 경로 전체 사용자" to total, "현재 필터에 맞는 사용자" to matched, "발생 사용자" to goals)) {
            assertTrue(Regex("${Regex.escape(label)}\\s*<strong[^>]*>$count</strong>").containsMatchIn(summary), summary)
        }
    }
    private fun href(html: String, label: String) = Regex("<a\\b[^>]*href=\"([^\"]+)\"[^>]*>\\s*${Regex.escape(label)}\\s*</a>")
        .find(html)?.groupValues?.get(1)?.let(HtmlUtils::htmlUnescape) ?: error("Missing link $label")
    private fun uri(route: String) = URI.create("http://127.0.0.1:${environment.getProperty("local.server.port")}$route")
    private fun params(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
    }
    private fun queryValues(route: String) = URI(route).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }
    private fun withQuery(route: String, values: Map<String, String>) = URI(route).rawPath + "?" + params(values)
    private fun get(client: HttpClient, route: String, prefix: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri(route)).timeout(Duration.ofSeconds(20))
        prefix?.let { request.header("X-Forwarded-Prefix", it) }
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun ok(client: HttpClient, route: String): String {
        val response = get(client, route)
        assertEquals(200, response.statusCode(), response.body())
        return response.body()
    }
    private fun loggedIn(user: String): HttpClient {
        val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
        val token = Regex("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").find(get(client, "/login").body())!!.groupValues[1]
        val response = client.send(HttpRequest.newBuilder(uri("/login")).header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(params(mapOf("username" to user, "password" to "prism-test-password", "_csrf" to token)))).build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(302, response.statusCode()); assertFalse(response.headers().firstValue("Location").orElse("").contains("error"))
        return client
    }
}
