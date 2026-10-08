package io.github.silbaram.prism.admin

import io.github.silbaram.prism.admin.service.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.admin.username=admin", "prism.schedule.enabled=false", "prism.analysis.finalization-enabled=false",
    "prism.admin.password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "prism.admin.viewer-username=viewer", "prism.admin.viewer-password-hash=\$2b\$04\$LRstVyy4zR4QXm44gykK2ONHlIxElNIiv5nBw.kpCCZzshC5cMXHS",
    "spring.datasource.url=jdbc:h2:mem:savedfunnels;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "spring.jpa.properties.hibernate.show_sql=false",
    "server.forward-headers-strategy=framework"
])
class SavedFunnelIntegrationTest {
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    @Autowired lateinit var repository: SavedFunnelRepository
    @Autowired lateinit var service: SavedFunnelService
    @Autowired lateinit var environment: Environment
    private lateinit var experiment: ExperimentEntity
    private lateinit var other: ExperimentEntity
    private val from = LocalDateTime.of(2026, 1, 1, 0, 0, 0, 123456000)
    private val until = from.plusDays(2)
    private val base get() = "/admin/experiments/${experiment.id}/funnels"
    private val fields get() = mapOf("name" to "Checkout", "description" to "Cart to purchase",
        "steps" to "cart\ncheckout\npurchase", "windowHours" to "24", "periodMode" to "FIXED",
        "from" to from.toString(), "until" to until.toString())
    private val input get() = SavedFunnelInput("Checkout", "Cart to purchase", listOf("cart", "checkout", "purchase"),
        24, SavedFunnelPeriod.FIXED, from, until)

    @BeforeEach fun clean() {
        repository.deleteAll(); conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll()
        fun create(key: String) = ExperimentEntity(key = key, description = "", goalEventName = "purchase").apply {
            addVariant(VariantEntity(name = "A", weight = 100))
        }.let(experiments::saveAndFlush)
        experiment = create("saved-funnel"); other = create("saved-funnel-other")
    }

    @Test fun `admin creates runs copies updates and deletes saved funnel without altering exact steps or precision`() {
        loggedIn("admin").use { client ->
            val exactSteps = listOf(" cart ", "Cart", "purchase")
            val created = create(client, fields + mapOf("name" to "  Checkout <script>alert(1)</script>  ",
                "description" to "<img src=x onerror=alert(1)>", "steps" to exactSteps.joinToString("\n \n") + "\n"))
            val saved = service.get(experiment.id!!, id(created))
            assertEquals("Checkout <script>alert(1)</script>", saved.name)
            assertEquals(exactSteps, saved.steps)
            assertEquals(from, saved.fromAt); assertEquals(until, saved.untilAt)
            val shown = impressions.saveAndFlush(ImpressionLogEntity(experimentKey = experiment.key, variant = "A",
                userId = "buyer", timestamp = from.minusHours(1)))
            conversions.saveAllAndFlush(exactSteps.mapIndexed { index, name -> ConversionLogEntity(
                experimentKey = experiment.key, variant = "A", userId = "buyer", eventName = name,
                timestamp = from.plusHours(index.toLong()), impressionId = shown.id) })
            assertEquals(listOf(1L, 1L, 1L), service.run(experiment.id!!, saved.id).report.groups.single().stages.map { it.users })
            val run = ok(client, created)
            assertTrue(run.contains("&lt;script&gt;")); assertFalse(run.contains("<script>alert(1)</script>"))
            assertEquals(exactSteps.joinToString("\n"), formValue(run, "steps"))
            for (name in listOf("from", "until")) {
                assertEquals(fields.getValue(name), formValue(run, name))
                assertTrue(inputTag(run, name).contains("type=\"text\""), "Microsecond precision must survive the browser")
            }
            val copyForm = ok(client, "$created/copy")
            assertEquals(1, service.list(experiment.id!!).items.size, "Opening copy must not write data")
            assertEquals(exactSteps.joinToString("\n"), formValue(copyForm, "steps"))
            assertEquals(from.toString(), formValue(copyForm, "from"))
            val copy = create(client, fields + mapOf("name" to "Copied", "steps" to exactSteps.joinToString("\n")))
            val edit = ok(client, "$copy/edit")
            assertEquals(exactSteps.joinToString("\n"), formValue(edit, "steps"))
            val changed = post(client, copy, fields + mapOf("name" to "Renamed", "description" to "updated",
                "version" to formValue(edit, "version")))
            assertEquals(302, changed.statusCode(), changed.body())
            val updated = service.get(experiment.id!!, id(copy))
            assertEquals("Renamed", updated.name); assertEquals("updated", updated.description)
            val deleted = post(client, "$copy/delete", mapOf("version" to updated.version.toString()))
            assertEquals(302, deleted.statusCode(), deleted.body())
            assertEquals(base, path(deleted.headers().firstValue("Location").orElseThrow()))
            assertEquals(404, get(client, copy).statusCode())
            assertEquals(listOf(saved.id), service.list(experiment.id!!).items.map { it.id })
        }
    }

    @Test fun `names are unique after label trim within one experiment and remain case sensitive`() {
        loggedIn("admin").use { client ->
            create(client)
            val duplicate = post(client, base, fields + ("name" to "  Checkout  "))
            assertEquals(400, duplicate.statusCode(), duplicate.body())
            assertEquals("  Checkout  ", formValue(duplicate.body(), "name"))
            create(client, fields + ("name" to "checkout"))
            val another = post(client, "/admin/experiments/${other.id}/funnels", fields)
            assertEquals(302, another.statusCode(), another.body())
            assertEquals(setOf("Checkout", "checkout"), service.list(experiment.id!!).items.map { it.name }.toSet())
            assertEquals("Checkout", service.list(other.id!!).items.single().name)
        }
    }

    @Test fun `invalid submitted values render a recoverable escaped form and never persist partial data`() {
        loggedIn("admin").use { client ->
            val bad = fields + mapOf("name" to " <script>alert(1)</script> ", "description" to "<img src=x>",
                "steps" to " cart \n\npurchase ", "windowHours" to "not-a-number", "from" to "not-a-date")
            val response = post(client, base, bad)
            assertEquals(400, response.statusCode(), response.body())
            for (name in listOf("name", "description", "steps", "windowHours", "from", "until")) {
                assertEquals(bad.getValue(name), formValue(response.body(), name), name)
            }
            assertTrue(inputTag(response.body(), "from").contains("type=\"text\""))
            assertFalse(response.body().contains("<script>alert(1)</script>"))
            assertFalse(response.body().contains("<img src=x>"))
            assertEquals(0L, repository.count())
            // The rejection page itself contains a valid CSRF token and can be corrected and resubmitted.
            assertEquals(302, postRaw(client, base, fields + ("_csrf" to csrf(response.body()))).statusCode())
        }
    }

    @Test fun `saved funnel validates every stored boundary and requires valid optimistic versions`() {
        loggedIn("admin").use { client ->
            for (bad in listOf(mapOf("name" to " "), mapOf("name" to "x".repeat(121)),
                mapOf("description" to "x".repeat(1001)), mapOf("steps" to "cart\ncart"),
                mapOf("steps" to (1..9).joinToString("\n") { "event-$it" }), mapOf("steps" to "x".repeat(256) + "\npurchase"),
                mapOf("windowHours" to "0"), mapOf("windowHours" to "721"), mapOf("periodMode" to "INVALID"),
                mapOf("from" to ""), mapOf("until" to from.toString()), mapOf("until" to "2999-01-01T00:00:00"),
                mapOf("from" to "1969-12-31T23:59:59"), mapOf("until" to from.plusDays(367).toString()),
                mapOf("from" to from.plusNanos(1).toString()))) {
                val response = post(client, base, fields + bad)
                assertEquals(400, response.statusCode(), bad.toString() + response.body())
                assertFalse(response.body().contains("org.springframework"))
            }
            assertEquals(0L, repository.count())
            val route = create(client)
            for (version in listOf(null, "bad", "-1")) {
                val values = if (version == null) emptyMap() else mapOf("version" to version)
                assertEquals(400, post(client, route, fields + values).statusCode())
                assertEquals(400, post(client, "$route/delete", values).statusCode())
            }
            assertEquals("Checkout", service.get(experiment.id!!, id(route)).name)
        }
    }

    @Test fun `viewer reads saved analyses but cannot access editing copying or any mutation and admin needs csrf`() {
        val saved = service.create(experiment.id!!, input)
        val route = "$base/${saved.id}"
        HttpClient.newHttpClient().use { assertEquals(302, get(it, base).statusCode()) }
        loggedIn("viewer").use { client ->
            val list = ok(client, base)
            ok(client, route)
            assertFalse(list.contains("href=\"$base/new\""))
            for (edit in listOf("$base/new", "$route/edit", "$route/copy")) {
                assertEquals(403, get(client, edit).statusCode(), edit)
            }
            val values = fields + mapOf("version" to saved.version.toString(), "_csrf" to csrf(list))
            for (target in listOf(base, route, "$route/delete")) {
                assertEquals(403, postRaw(client, target, values).statusCode(), target)
            }
        }
        loggedIn("admin").use { client ->
            for (target in listOf(base, route, "$route/delete")) {
                assertEquals(403, postRaw(client, target, fields + ("version" to saved.version.toString())).statusCode(), target)
            }
        }
        assertEquals(listOf(saved.id), service.list(experiment.id!!).items.map { it.id })
    }

    @Test fun `saved IDs never leak across experiments for reads edits copies updates or deletion`() {
        val saved = service.create(experiment.id!!, input)
        val wrong = "/admin/experiments/${other.id}/funnels/${saved.id}"
        loggedIn("admin").use { client ->
            for (route in listOf(wrong, "$wrong/edit", "$wrong/copy", "/admin/experiments/999999999/funnels")) {
                assertEquals(404, get(client, route).statusCode(), route)
            }
            assertEquals(404, post(client, wrong, fields + ("version" to saved.version.toString())).statusCode())
            assertEquals(404, post(client, "$wrong/delete", mapOf("version" to saved.version.toString())).statusCode())
        }
        assertEquals(saved.id, service.get(experiment.id!!, saved.id).id)
    }

    @Test fun `stale updates preserve submitted inputs and stale deletes cannot remove newer changes`() {
        val original = service.create(experiment.id!!, input)
        val route = "$base/${original.id}"
        loggedIn("admin").use { client ->
            val first = post(client, route, fields + mapOf("name" to "First change", "version" to original.version.toString()))
            assertEquals(302, first.statusCode(), first.body())
            val current = service.get(experiment.id!!, original.id)
            assertTrue(current.version > original.version)
            val stale = fields + mapOf("name" to "Unsubmitted draft", "steps" to " cart \npurchase", "windowHours" to "48",
                "version" to original.version.toString())
            val conflict = post(client, route, stale)
            assertEquals(409, conflict.statusCode(), conflict.body())
            for (name in listOf("name", "steps", "windowHours", "from", "until", "version")) {
                assertEquals(stale.getValue(name), formValue(conflict.body(), name), name)
            }
            assertTrue(conflict.body().contains("href=\"$route/edit\""), "Conflict form must allow reloading the current version")
            assertEquals(409, post(client, "$route/delete", mapOf("version" to original.version.toString())).statusCode())
            assertEquals("First change", service.get(experiment.id!!, original.id).name)
            assertEquals(302, post(client, "$route/delete", mapOf("version" to current.version.toString())).statusCode())
        }
    }

    @Test fun `relative periods resolve per run while generated drilldowns retain one fixed snapshot`() {
        val now = LocalDateTime.of(2026, 6, 1, 12, 30, 40, 123456000)
        for ((mode, days) in listOf(SavedFunnelPeriod.LAST_7_DAYS to 7L, SavedFunnelPeriod.LAST_30_DAYS to 30L)) {
            val saved = service.create(experiment.id!!, input.copy(name = mode.name, periodMode = mode))
            assertNull(saved.fromAt); assertNull(saved.untilAt)
            val run = service.run(experiment.id!!, saved.id, now)
            assertEquals(now.minusDays(days), run.query.from); assertEquals(now, run.query.until)
            val later = service.run(experiment.id!!, saved.id, now.plusHours(1))
            assertEquals(run.query.from.plusHours(1), later.query.from)
            assertEquals(run.query.until.plusHours(1), later.query.until)
            assertEquals(run.query, run.report.query)
        }
        loggedIn("admin").use { client ->
            val route = create(client, fields + mapOf("name" to "HTTP rolling", "periodMode" to "LAST_7_DAYS", "from" to "ignored", "until" to "ignored"))
            val saved = service.get(experiment.id!!, id(route))
            assertNull(saved.fromAt); assertNull(saved.untilAt)
            val report = ok(client, route)
            val link = Regex("href=\"([^\"]*/funnel/users[^\"]+)\"").find(report)!!.groupValues[1].let(::unescape)
            val query = queryValues(link)
            assertEquals(formValue(report, "from"), query["from"])
            assertEquals(formValue(report, "until"), query["until"])
            assertEquals(Duration.ofDays(7), Duration.between(LocalDateTime.parse(query.getValue("from")), LocalDateTime.parse(query.getValue("until"))))
            // A saved definition can change later; existing drill-down links must still use their original analysis parameters.
            service.update(experiment.id!!, saved.id, saved.version, input.copy(name = saved.name, steps = listOf("other", "purchase")))
            val detail = ok(client, link)
            assertEquals("cart\ncheckout\npurchase", query["steps"])
            assertTrue(detail.contains(query.getValue("from")))
            assertTrue(detail.contains(query.getValue("until")))
        }
    }

    @Test fun `list pagination and forwarded prefix retain working routes without duplicating saved definitions`() {
        repeat(51) { service.create(experiment.id!!, input.copy(name = "Funnel $it")) }
        val page = service.list(experiment.id!!)
        assertEquals(50, page.items.size); assertNotNull(page.nextId)
        val next = service.list(experiment.id!!, page.nextId)
        assertEquals(1, next.items.size); assertNull(next.nextId)
        assertEquals(51, (page.items + next.items).map { it.id }.distinct().size)
        loggedIn("admin").use { client ->
            val response = get(client, base, prefix = "/prism")
            assertEquals(200, response.statusCode(), response.body())
            val links = Regex("href=\"([^\"]*/funnels[^\"]*)\"").findAll(response.body()).map { unescape(it.groupValues[1]) }.toList()
            assertTrue(links.isNotEmpty())
            assertTrue(links.all { it.startsWith("/prism$base") }, links.toString())
            val nextLink = links.single { queryValues(it).containsKey("afterId") }
            assertEquals(page.nextId.toString(), queryValues(nextLink)["afterId"])
            assertEquals(200, get(client, nextLink.removePrefix("/prism"), prefix = "/prism").statusCode())
            for (cursor in listOf("bad", "0", "-1")) assertEquals(400, get(client, "$base?afterId=$cursor").statusCode())
            val created = post(client, base, fields + ("name" to "Proxy-created"), prefix = "/prism")
            assertEquals(302, created.statusCode(), created.body())
            assertTrue(path(created.headers().firstValue("Location").orElseThrow()).startsWith("/prism$base/"))
        }
    }

    @Test fun `fixed epoch boundary remains a valid saved and runnable query`() {
        val epoch = LocalDateTime.of(1970, 1, 1, 0, 0)
        loggedIn("admin").use { client ->
            val route = create(client, fields + mapOf("from" to epoch.toString(), "until" to epoch.plusDays(1).toString()))
            val saved = service.get(experiment.id!!, id(route))
            assertEquals(epoch, saved.fromAt); assertEquals(epoch.plusDays(1), saved.untilAt)
            val report = service.run(experiment.id!!, saved.id)
            assertEquals(epoch, report.query.from); assertEquals(epoch.plusDays(1), report.query.until)
            assertEquals(0L, report.report.groups.single().stages.first().users)
            for (page in listOf(route, "$route/edit")) {
                val html = ok(client, page)
                assertEquals(epoch.toString(), formValue(html, "from"))
                assertEquals(epoch.plusDays(1).toString(), formValue(html, "until"))
            }
        }
    }

    @Test fun `deleting an unused draft cascades its saved funnels and preserves another experiment definitions`() {
        val first = service.create(experiment.id!!, input)
        val second = service.create(experiment.id!!, input.copy(name = "Another funnel"))
        val retained = service.create(other.id!!, input)
        assertEquals(ExperimentStatus.DRAFT, experiment.status)
        assertFalse(impressions.existsByExperimentKey(experiment.key))
        experiments.deleteById(experiment.id!!)
        experiments.flush()
        assertFalse(repository.existsById(first.id)); assertFalse(repository.existsById(second.id))
        assertTrue(experiments.existsById(other.id!!))
        assertEquals(retained.id, service.get(other.id!!, retained.id).id)
        assertEquals(listOf(retained.id), repository.findAll().map { it.id })
    }

    @Test fun `copy name truncation preserves complete supplementary characters within the label limit`() {
        loggedIn("admin").use { client ->
            for ((original, expected) in listOf(
                "x".repeat(113) + "😀" to "x".repeat(113) + " (복사본)",
                "x".repeat(112) + "😀" to "x".repeat(112) + "😀 (복사본)")) {
                val saved = service.create(experiment.id!!, input.copy(name = original))
                val copiedName = formValue(ok(client, "$base/${saved.id}/copy"), "name")
                assertEquals(expected, copiedName, "Truncating a UTF-16 pair must not turn it into a replacement character")
                assertTrue(copiedName.length <= 120)
                assertTrue(copiedName.endsWith(" (복사본)"))
                assertTrue(copiedName.codePoints().toArray().none { it in 0xD800..0xDFFF })
                // The generated default is itself acceptable to the normal create endpoint.
                create(client, fields + ("name" to copiedName))
            }
        }
    }

    @Test fun `maximum accepted Korean steps survive saved run drilldown timeline return and prefilled form`() {
        val steps = (1..8).map { "단".repeat(254) + it }
        assertTrue(steps.all { it.length == 255 })
        val saved = service.create(experiment.id!!, input.copy(steps = steps))
        val shown = impressions.saveAndFlush(ImpressionLogEntity(experimentKey = experiment.key, variant = "A",
            userId = "long-funnel-user", timestamp = from.minusHours(1)))
        conversions.saveAllAndFlush(steps.mapIndexed { index, event -> ConversionLogEntity(
            experimentKey = experiment.key, variant = "A", userId = shown.userId, eventName = event,
            timestamp = from.plusHours(index.toLong()), impressionId = shown.id) })
        loggedIn("admin").use { client ->
            val run = ok(client, "$base/${saved.id}")
            assertEquals(steps.joinToString("\n"), formValue(run, "steps"))
            val usersLink = Regex("href=\"([^\"]*/funnel/users[^\"]+)\"").findAll(run)
                .map { unescape(it.groupValues[1]) }.first {
                    val values = queryValues(it)
                    values["stage"] == "8" && values["selection"] == "REACHED"
                }
            assertTrue(usersLink.length > 18_000, "Exercise the fully percent-encoded maximum accepted names")
            val users = ok(client, usersLink)
            val timelineLink = Regex("href=\"([^\"]*/journeys/user[^\"]+)\"").find(users)!!.groupValues[1].let(::unescape)
            val timeline = ok(client, timelineLink)
            assertTrue(timeline.contains("모든 단계 도달"))
            val returnLink = Regex("href=\"([^\"]*/funnel/users[^\"]+)\"").find(timeline)!!.groupValues[1].let(::unescape)
            assertEquals(steps.joinToString("\n"), queryValues(returnLink)["steps"])
            assertTrue(ok(client, returnLink).contains(shown.userId))
            val prefilled = ok(client, "$base/new?" + params(fields + ("steps" to steps.joinToString("\n"))))
            assertEquals(steps.joinToString("\n"), formValue(prefilled, "steps"))
        }
    }

    private fun create(client: HttpClient, values: Map<String, String> = fields): String {
        val response = post(client, base, values)
        assertEquals(302, response.statusCode(), response.body())
        return path(response.headers().firstValue("Location").orElseThrow())
    }
    private fun id(route: String) = route.substringAfterLast('/').toLong()
    private fun path(location: String) = URI(location).rawPath
    private fun uri(route: String) = URI.create("http://127.0.0.1:${environment.getProperty("local.server.port")}$route")
    private fun params(values: Map<String, String>) = values.entries.joinToString("&") { (key, value) ->
        URLEncoder.encode(key, Charsets.UTF_8) + "=" + URLEncoder.encode(value, Charsets.UTF_8)
    }
    private fun queryValues(route: String) = URI(route).rawQuery.orEmpty().split('&').filter(String::isNotEmpty).associate {
        URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
    }
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
    private fun post(client: HttpClient, route: String, values: Map<String, String>, prefix: String? = null) =
        postRaw(client, route, values + ("_csrf" to csrf(ok(client, "/admin/experiments"))), prefix)
    private fun postRaw(client: HttpClient, route: String, values: Map<String, String>, prefix: String? = null): HttpResponse<String> {
        val request = HttpRequest.newBuilder(uri(route)).timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/x-www-form-urlencoded").header("Accept", "text/html")
        prefix?.let { request.header("X-Forwarded-Prefix", it) }
        return client.send(request.POST(HttpRequest.BodyPublishers.ofString(params(values))).build(), HttpResponse.BodyHandlers.ofString())
    }
    private fun csrf(html: String) = Regex("name=\"_csrf\"[^>]*value=\"([^\"]+)\"").find(html)!!.groupValues[1]
    private fun loggedIn(user: String): HttpClient {
        val client = HttpClient.newBuilder().cookieHandler(CookieManager(null, CookiePolicy.ACCEPT_ALL)).build()
        val login = postRaw(client, "/login", mapOf("username" to user, "password" to "prism-test-password", "_csrf" to csrf(get(client, "/login").body())))
        assertEquals(302, login.statusCode()); assertFalse(login.headers().firstValue("Location").orElse("").contains("error"))
        return client
    }
    private fun unescape(value: String) = org.springframework.web.util.HtmlUtils.htmlUnescape(value)
    private fun inputTag(html: String, name: String): String = Regex("<input\\b[^>]*>").findAll(html)
        .firstOrNull { Regex("\\bname=\"${Regex.escape(name)}\"").containsMatchIn(it.value) }?.value
        ?: error("Missing input $name in $html")
    private fun formValue(html: String, name: String): String {
        val textarea = Regex("<textarea\\b(?=[^>]*\\bname=\"${Regex.escape(name)}\")[^>]*>([\\s\\S]*?)</textarea>").find(html)
        if (textarea != null) return unescape(textarea.groupValues[1])
        return Regex("\\bvalue=\"([^\"]*)\"").find(inputTag(html, name))?.groupValues?.get(1)?.let(::unescape).orEmpty()
    }
}
