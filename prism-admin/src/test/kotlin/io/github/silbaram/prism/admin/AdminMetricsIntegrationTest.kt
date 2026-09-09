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
    fun clean() { conversions.deleteAll(); impressions.deleteAll(); experiments.deleteAll() }

    @Test
    fun `goal form binds saves updates and renders metric and event pages`() {
        assertTrue(get("/admin/experiments/new").contains("name=\"goalEventName\""))
        val fields = mapOf("key" to "checkout", "description" to "test", "goalEventName" to "purchase",
            "variants[0].name" to "A", "variants[0].weight" to "100")
        val created = post("/admin/experiments", fields)
        assertEquals(302, created.statusCode(), created.body())
        val experiment = experiments.findByKey("checkout")!!
        assertEquals("purchase", experiment.goalEventName)
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
        assertEquals(302, updated.statusCode(), updated.body())
        assertEquals("signup", experiments.findByKey("checkout")!!.goalEventName)
        assertTrue(get("/admin/experiments/${experiment.id}").contains("0.00%"))
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
}
