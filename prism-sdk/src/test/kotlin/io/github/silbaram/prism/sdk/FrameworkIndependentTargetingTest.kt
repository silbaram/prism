package io.github.silbaram.prism.sdk

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.common.rest.ResponseCode
import io.github.silbaram.prism.common.rest.dto.assign.AssignmentResponse
import io.github.silbaram.prism.common.rest.dto.config.*
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.core.targeting.*
import okhttp3.mockwebserver.*
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class FrameworkIndependentTargetingTest {
    private val mapper = jacksonObjectMapper()
    private fun options(evaluator: TargetingEvaluator) = PrismClientOptions(
        targetingEvaluator = evaluator, configSyncInterval = Duration.ofHours(1),
        eventFlushInterval = Duration.ofHours(1), initializationTimeout = Duration.ofSeconds(2))

    private fun experiment(key: String, rule: String) = ExperimentConfig(key, "ACTIVE", listOf(VariantConfig("A", 100)), listOf(rule))

    @Test fun `invalid rules block only their experiment and cannot manufacture exposure`() {
        val portable = """prism:v1:{"attribute":"country","op":"eq","value":"KR"}"""
        val config = AtomicReference(ConfigResponse("a".repeat(64), listOf(
            experiment("portable", portable), experiment("legacy", "country == 'KR'"), experiment("unknown", "prism:v2:{}"))))
        val events = CopyOnWriteArrayList<ClientEvent>()
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(config, events)
            server.start()
            PrismClient(server.url("/").toString(), options = options(JsonTargetingEvaluator)).use { client ->
                assertTrue(client.refreshConfig())
                assertEquals(setOf("legacy", "unknown"), client.targetingConfigurationErrors.keys)
                assertEquals("9997", client.assign("u", "legacy", mapOf("country" to "KR")).resultCode)
                assertEquals("9997", client.evaluate("u", "unknown").resultCode)
                val forged = AssignmentResponse("forged", "legacy", "A", ResponseCode.SUCCESS.code, "", "a".repeat(64))
                assertFalse(client.recordExposure(forged))
                val historic = forged.copy(exposureEventId = "10000000-0000-0000-0000-000000000001")
                // A previous exposure must not turn a current unsupported-rule fallback into a conversion.
                assertFalse(client.trackConversion(historic, "purchase"))
                assertFalse(client.trackConversion("forged", "legacy", "purchase"))
                assertNull(client.assign("excluded", "portable", mapOf("country" to "US")).variant)
                val valid = client.assign("eligible", "portable", mapOf("country" to "KR"))
                assertEquals("A", valid.variant)
                assertTrue(client.trackConversion(valid, "purchase"))
                assertTrue(client.flush())
                assertEquals(listOf("exposure", "conversion"), events.map { it.type })
                assertTrue(events.all { it.experimentKey == "portable" })
                // Rejecting a rule must not retain old active configurations after a pause.
                config.set(ConfigResponse("b".repeat(64), emptyList(), revision = 1))
                assertTrue(client.refreshConfig())
                assertTrue(client.targetingConfigurationErrors.isEmpty())
                assertNull(client.assign("later", "portable", mapOf("country" to "KR")).variant)
            }
        }
    }

    @Test fun `custom evaluator is scoped to its SDK client`() {
        val config = AtomicReference(ConfigResponse("a".repeat(64), listOf(experiment("custom", "custom-rule"))))
        val events = CopyOnWriteArrayList<ClientEvent>()
        val evaluator = object : TargetingEvaluator {
            override fun validateSyntax(condition: String) { require(condition == "custom-rule") }
            override fun evaluate(rule: TargetingRule, context: UserContext) = context.attributes["country"] == "KR"
        }
        MockWebServer().use { server ->
            server.dispatcher = dispatcher(config, events)
            server.start()
            PrismClient(server.url("/").toString(), options = options(evaluator)).use { custom ->
                PrismClient(server.url("/").toString(), options = options(JsonTargetingEvaluator)).use { dataOnly ->
                    assertEquals("A", custom.assign("u", "custom", mapOf("country" to "KR")).variant)
                    assertNull(custom.assign("other", "custom", mapOf("country" to "US")).variant)
                    assertEquals("9997", dataOnly.assign("u", "custom", mapOf("country" to "KR")).resultCode)
                    assertTrue(custom.flush())
                    assertEquals(1, events.size)
                }
            }
        }
    }

    private fun dispatcher(config: AtomicReference<ConfigResponse>, events: MutableList<ClientEvent>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
            "/v1/config" -> MockResponse().setBody(mapper.writeValueAsString(config.get()))
            "/v1/events" -> {
                val batch = mapper.readValue<EventsRequest>(request.body.readUtf8()).events
                events.addAll(batch)
                MockResponse().setBody(mapper.writeValueAsString(EventsResponse(batch.map { EventResult(it.eventId, EventStatus.ACCEPTED) })))
            }
            else -> MockResponse().setResponseCode(404)
        }
    }
}
