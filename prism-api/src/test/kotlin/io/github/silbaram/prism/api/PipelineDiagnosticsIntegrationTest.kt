package io.github.silbaram.prism.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.api.pipeline.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.Environment
import java.net.URI
import java.net.http.*
import java.time.*

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = [
    "prism.api.keys=prism-test-api-key-0123456789abcdef",
    "prism.monitoring.keys=prism-test-monitor-key-0123456789abcdef",
    "spring.datasource.url=jdbc:h2:mem:pipeline-diagnostics;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never", "spring.jpa.properties.hibernate.show_sql=false"
])
class PipelineDiagnosticsIntegrationTest {
    @Autowired lateinit var inbox: PipelineInboxRepository
    @Autowired lateinit var outbox: PipelineOutboxRepository
    @Autowired lateinit var environment: Environment
    private val mapper = jacksonObjectMapper()
    private fun get(path: String = "/api/internal/pipeline/diagnostics", key: String? = "prism-test-monitor-key-0123456789abcdef"): HttpResponse<String> =
        HttpClient.newHttpClient().use { client ->
            val request = HttpRequest.newBuilder(URI.create("http://localhost:${environment.getProperty("local.server.port")}$path"))
                .timeout(Duration.ofSeconds(10)).GET()
            key?.let { request.header("X-Prism-Api-Key", it) }
            client.send(request.build(), HttpResponse.BodyHandlers.ofString())
        }

    @BeforeEach fun clean() { inbox.deleteAll(); outbox.deleteAll() }

    @Test fun `only dedicated monitoring keys can query diagnostics and cannot read SDK configuration`() {
        assertEquals(401, get(key = null).statusCode())
        assertEquals(401, get(key = "prism-test-api-key-0123456789abcdef").statusCode())
        assertEquals(401, get("/v1/config").statusCode())
        val response = get()
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.headers().firstValue("Cache-Control").orElse("").contains("no-store"))
    }

    @Test fun `snapshot separates processing retry history rejection and pending exports without payloads`() {
        val now = LocalDateTime.now(ZoneOffset.UTC)
        inbox.saveAll(listOf(
            PipelineInboxEntity("pending", "private-event-id", "private-user-payload", createdAt = now.minusSeconds(125)),
            PipelineInboxEntity("retry", "event2", "private-user-payload", attempts = 2, retryAt = now.plusHours(1), createdAt = now.minusSeconds(60)),
            PipelineInboxEntity("done", "event3", "private-user-payload", status = "DONE"),
            PipelineInboxEntity("rejected", "event4", "private-user-payload", status = "REJECTED")))
        outbox.saveAll(listOf(PipelineOutboxEntity("warehouse", "WAREHOUSE", "private-user-payload", now.minusSeconds(60)),
            PipelineOutboxEntity("dead-letter", "DEAD_LETTER", "private-user-payload", null)))
        val response = get()
        assertEquals(200, response.statusCode(), response.body())
        val json = mapper.readTree(response.body())
        assertEquals("DIRECT", json["mode"].asText())
        assertFalse(json["consumerEnabled"].asBoolean())
        assertEquals(2, json["inbox"]["pendingCount"].asInt())
        assertEquals(1, json["inbox"]["dueCount"].asInt())
        assertEquals(1, json["inbox"]["attemptedPendingCount"].asInt())
        assertEquals(1, json["inbox"]["completedCount"].asInt())
        assertEquals(1, json["inbox"]["rejectedCount"].asInt())
        assertTrue(json["inbox"]["oldestPendingAgeSeconds"].asLong() in 125..135)
        assertEquals(2, json["outbox"]["pendingCount"].asInt())
        assertEquals(1, json["outbox"]["deadLetterPendingCount"].asInt())
        assertEquals(1, json["outbox"]["warehousePendingCount"].asInt())
        assertEquals(1, json["outbox"]["unknownAgeCount"].asInt())
        assertTrue(json["outbox"]["oldestKnownPendingAgeSeconds"].asLong() in 60..70)
        assertFalse(response.body().contains("private-"))
    }

    @Test fun `empty queues have unknown ages rather than a fabricated zero age`() {
        val response = get()
        assertEquals(200, response.statusCode())
        val json = mapper.readTree(response.body())
        assertEquals(0, json["inbox"]["pendingCount"].asInt())
        assertTrue(json["inbox"]["oldestPendingAgeSeconds"].isNull)
        assertTrue(json["outbox"]["oldestKnownPendingAgeSeconds"].isNull)
    }
}
