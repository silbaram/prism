package io.github.silbaram.prism.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.api.pipeline.*
import io.github.silbaram.prism.common.rest.dto.event.ClientEvent
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.doAnswer
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Tag("kafka")
@ExtendWith(OutputCaptureExtension::class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = [
    "prism.pipeline.mode=KAFKA",
    "spring.datasource.url=jdbc:h2:mem:kafka-shutdown;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"
])
class KafkaPipelineShutdownIntegrationTest {
    @Autowired lateinit var pipeline: KafkaPipeline
    @Autowired lateinit var properties: PipelineProperties
    @Autowired lateinit var transport: KafkaTransport
    @MockitoSpyBean lateinit var storage: PipelineStorage
    @Autowired lateinit var inbox: PipelineInboxRepository
    @Autowired lateinit var outbox: PipelineOutboxRepository
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository

    companion object {
        private val prefix = "prism-shutdown-${UUID.randomUUID()}"
        @JvmStatic @DynamicPropertySource fun properties(registry: DynamicPropertyRegistry) {
            registry.add("prism.pipeline.bootstrap-servers") { System.getenv("PRISM_TEST_KAFKA_BOOTSTRAP") }
            registry.add("prism.pipeline.topic") { "$prefix-events" }
            registry.add("prism.pipeline.warehouse-topic") { "$prefix-warehouse" }
            registry.add("prism.pipeline.dead-letter-topic") { "$prefix-dead-letter" }
            registry.add("prism.pipeline.group-id") { "$prefix-workers" }
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos()
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(50)
        assertTrue(condition(), "Expected durable processing to complete")
    }

    @Test
    fun `shutdown finishes in-flight admission and restart safely replays uncommitted records`(output: CapturedOutput) {
        experiments.save(ExperimentEntity(key = "checkout", description = "", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "A", weight = 100))
        })
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val interrupted = AtomicBoolean()
        val receiveCalls = AtomicInteger()
        doAnswer { call ->
            receiveCalls.incrementAndGet()
            entered.countDown()
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS), "Admission was never released")
                call.callRealMethod()
            } catch (error: InterruptedException) {
                interrupted.set(true)
                throw error
            } finally { finished.countDown() }
            null
        }.`when`(storage).receive(anyString(), anyString())

        val event = ClientEvent(UUID.randomUUID().toString(), "exposure", "u", "checkout", "A",
            Instant.now().toString(), "a".repeat(64))
        val payload = jacksonObjectMapper().writeValueAsString(event)
        val closer = Executors.newSingleThreadExecutor()
        val closeStarted = CountDownLatch(1)
        var closing: Future<*>? = null
        var restarted: KafkaPipeline? = null
        try {
            transport.send(properties.topic, event.eventId, payload).get(10, TimeUnit.SECONDS)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Consumer did not start admission")
            closing = closer.submit { closeStarted.countDown(); pipeline.close() }
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS))
            assertThrows(TimeoutException::class.java) { closing.get(250, TimeUnit.MILLISECONDS) }
            assertFalse(interrupted.get(), "Graceful shutdown interrupted an admitted record")
            release.countDown()
            closing.get(8, TimeUnit.SECONDS)
            assertTrue(finished.await(1, TimeUnit.SECONDS))
            assertFalse(interrupted.get())
            assertEquals(1L, inbox.count(), "The in-flight record must reach the durable inbox")

            // Same broker group and DB: the admitted record's offset was not committed during close.
            restarted = KafkaPipeline(properties, storage, transport, inbox, outbox)
            transport.send(properties.topic, event.eventId, payload).get(10, TimeUnit.SECONDS)
            await { receiveCalls.get() >= 3 && impressions.count() == 1L &&
                inbox.count() == 1L && inbox.findAll().single().status == "DONE" && outbox.count() == 0L }
            assertEquals(event.eventId, impressions.findByEventId(event.eventId)?.eventId)
        } finally {
            release.countDown()
            try { closing?.get(15, TimeUnit.SECONDS) }
            finally { restarted?.close(); pipeline.close(); closer.shutdownNow() }
        }
        assertFalse(output.all.contains("Failed to close coordinator"), "Consumer coordinator close logged an error")
        assertFalse(output.all.contains("Failed to close fetcher"), "Consumer fetcher close logged an error")
        assertFalse(output.all.contains("InterruptException"), "Normal shutdown must not interrupt Kafka close")
    }
}
