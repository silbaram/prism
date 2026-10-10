package io.github.silbaram.prism.starter

import io.github.silbaram.prism.sdk.PrismExperimentClient
import io.github.silbaram.prism.sdk.PrismClient
import io.github.silbaram.prism.sdk.EvaluationMode
import java.time.Duration
import io.github.silbaram.prism.starter.aop.PrismExperimentAspect
import io.github.silbaram.prism.starter.service.PrismConversionTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import io.github.silbaram.prism.core.targeting.TargetingEvaluator
import io.github.silbaram.prism.core.targeting.TargetingRule
import io.github.silbaram.prism.core.targeting.UserContext
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class PrismAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PrismAutoConfiguration::class.java))

    @Test
    fun `custom targeting bean replaces the default evaluator in the SDK`() {
        val evaluator = object : TargetingEvaluator {
            override fun validateSyntax(condition: String) { require(condition == "custom-rule") }
            override fun evaluate(rule: TargetingRule, context: UserContext) = context.attributes["country"] == "KR"
        }
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/config") { exchange ->
            val body = """{"version":"${"a".repeat(64)}","experiments":[{"key":"custom","status":"ACTIVE","variants":[{"name":"A","weight":100}],"targetingRules":["custom-rule"]}]}""".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
            exchange.close()
        }
        server.start()
        try {
            contextRunner.withBean(TargetingEvaluator::class.java, { evaluator })
                .withPropertyValues("prism.client.url=http://127.0.0.1:${server.address.port}")
                .run { context ->
                    assertThat(context).hasNotFailed().hasSingleBean(TargetingEvaluator::class.java)
                    assertThat(context.getBean(TargetingEvaluator::class.java)).isSameAs(evaluator)
                    val client = context.getBean(PrismClient::class.java)
                    assertThat(client.evaluate("eligible", "custom", mapOf("country" to "KR")).variant).isEqualTo("A")
                    assertThat(client.evaluate("excluded", "custom", mapOf("country" to "US")).variant).isNull()
                }
        } finally { server.stop(0) }
    }

    @Test
    fun `should create PrismExperimentClient and related beans when url is configured`() {
        contextRunner
            .withPropertyValues("prism.client.url=http://localhost:8080")
            .run { context ->
                assertThat(context).hasSingleBean(PrismExperimentClient::class.java)
                assertThat(context).hasSingleBean(PrismExperimentAspect::class.java)
                assertThat(context).hasSingleBean(PrismConversionTracker::class.java)
            }
    }

    @Test
    fun `local evaluation properties bind and invalid queue limits fail at startup`() {
        contextRunner.withPropertyValues("prism.client.url=http://localhost:1", "prism.client.initialization-timeout=0s",
            "prism.client.config-sync-interval=15s", "prism.client.event-flush-interval=2s", "prism.client.event-batch-size=20",
            "prism.client.event-queue-capacity=200", "prism.client.shutdown-timeout=1s", "prism.client.flush-timeout=3s", "prism.client.exposure-dedup-capacity=123",
            "prism.client.api-key=prism-test-api-key-0123456789abcdef", "prism.client.config-streaming=true",
            "prism.client.sticky-assignments-directory=${java.nio.file.Files.createTempDirectory("prism-starter-sticky")}")
            .run { context ->
                assertThat(context).hasNotFailed()
                val properties = context.getBean(PrismProperties::class.java)
                assertThat(properties.evaluationMode).isEqualTo(EvaluationMode.LOCAL)
                assertThat(properties.configSyncInterval).isEqualTo(Duration.ofSeconds(15))
                assertThat(properties.initializationTimeout).isEqualTo(Duration.ZERO)
                assertThat(properties.flushTimeout).isEqualTo(Duration.ofSeconds(3))
                assertThat(properties.shutdownTimeout).isEqualTo(Duration.ofSeconds(1))
                assertThat(properties.eventBatchSize).isEqualTo(20)
                assertThat(properties.eventQueueCapacity).isEqualTo(200)
                assertThat(properties.exposureDedupCapacity).isEqualTo(123)
                assertThat(properties.configStreaming).isTrue()
                assertThat(java.nio.file.Files.isDirectory(java.nio.file.Path.of(properties.stickyAssignmentsDirectory!!))).isTrue()
                assertThat(properties.apiKey).isEqualTo("prism-test-api-key-0123456789abcdef")
                assertThat(properties.toString()).doesNotContain(properties.apiKey)
                assertThat(context.getBean(PrismClient::class.java).assign("u", "e").variant).isNull()
            }
        contextRunner.withPropertyValues("prism.client.url=http://localhost:1", "prism.client.flush-timeout=0s")
            .run { context -> assertThat(context).hasFailed() }
        contextRunner.withPropertyValues("prism.client.url=http://localhost:1", "prism.client.event-batch-size=0")
            .run { context -> assertThat(context).hasFailed() }
    }

    @Test
    fun `should not create beans when url is missing`() {
        contextRunner
            .run { context ->
                assertThat(context).doesNotHaveBean(PrismExperimentClient::class.java)
                assertThat(context).doesNotHaveBean(PrismExperimentAspect::class.java)
                assertThat(context).doesNotHaveBean(PrismConversionTracker::class.java)
            }
    }
}
