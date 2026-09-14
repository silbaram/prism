package io.github.silbaram.prism.api

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.github.silbaram.prism.api.pipeline.*
import io.github.silbaram.prism.common.rest.dto.event.ClientEvent
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.time.*
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = [
    "prism.pipeline.mode=KAFKA", "prism.pipeline.bootstrap-servers=127.0.0.1:1", "prism.pipeline.consumer-enabled=false",
    "spring.datasource.url=jdbc:h2:mem:pipeline-storage;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password=",
    "spring.datasource.hikari.maximum-pool-size=1", "spring.datasource.hikari.connection-timeout=500",
    "spring.jpa.hibernate.ddl-auto=create-drop", "spring.sql.init.mode=never"
])
class PipelineStorageIntegrationTest {
    @Autowired lateinit var storage: PipelineStorage
    @Autowired lateinit var pipeline: KafkaPipeline
    @Autowired lateinit var inbox: PipelineInboxRepository
    @Autowired lateinit var outbox: PipelineOutboxRepository
    @Autowired lateinit var experiments: ExperimentRepository
    @Autowired lateinit var impressions: ImpressionLogRepository
    @Autowired lateinit var conversions: ConversionLogRepository
    private val mapper = jacksonObjectMapper()

    private fun materializeReadyEvents() {
        // A coarse platform clock can still read below a newly rounded TIMESTAMP(6).
        // Make only unclaimed fixtures due explicitly; existing retry leases stay intact.
        inbox.findAll().filter { it.status == "PENDING" && it.attempts == 0 }.forEach {
            it.retryAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)
            inbox.saveAndFlush(it)
        }
        pipeline.materialize()
    }

    @Test fun `dependency retries and duplicate delivery are durable and an interrupted export can be retried`() {
        experiments.save(ExperimentEntity(key = "pipeline", description = "", status = ExperimentStatus.ACTIVE).apply {
            addVariant(VariantEntity(name = "A", weight = 100))
        })
        val exposure = ClientEvent(UUID.randomUUID().toString(), "exposure", "u", "pipeline", "A", Instant.now().toString(), "a".repeat(64))
        val conversion = exposure.copy(eventId = UUID.randomUUID().toString(), type = "conversion", eventName = "purchase", exposureEventId = exposure.eventId)
        storage.receive(mapper.writeValueAsString(conversion))
        materializeReadyEvents()
        assertEquals("PENDING", inbox.findAll().single().status)
        assertEquals(0, conversions.count())
        storage.receive(mapper.writeValueAsString(exposure))
        storage.receive(mapper.writeValueAsString(exposure))
        materializeReadyEvents()
        inbox.findAll().filter { it.status == "PENDING" }.forEach { it.retryAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1); inbox.save(it) }
        materializeReadyEvents()
        assertEquals(1, impressions.count())
        assertEquals(1, conversions.count())
        assertEquals(2, inbox.count())
        assertTrue(inbox.findAll().all { it.status == "DONE" })
        assertEquals(2, outbox.count())
        val exported = mutableListOf<String>()
        assertThrows(IllegalStateException::class.java) { storage.publish(exposure.eventId) { exported.add(it.payload); error("after remote acknowledgement") } }
        assertTrue(outbox.existsById(exposure.eventId))
        storage.publish(exposure.eventId) { exported.add(it.payload) }
        assertEquals(2, exported.size)
        assertEquals(exported[0], exported[1])
        assertFalse(outbox.existsById(exposure.eventId))
        storage.receive(mapper.writeValueAsString(exposure.copy(variant = "conflict")))
        materializeReadyEvents()
        assertEquals(1, inbox.findAll().count { it.status == "REJECTED" })
        assertEquals(1, impressions.count())
        storage.receive("not-json")
        storage.receive(mapper.writeValueAsString(exposure.copy(eventId = "not-a-uuid")))
        assertEquals(3, outbox.findAll().count { it.kind == "DEAD_LETTER" })
        // Persisted state left by a worker killed after claiming an event: do not steal
        // its live lease, but recover automatically once that lease has expired.
        val interrupted = exposure.copy(eventId = UUID.randomUUID().toString(), userId = "interrupted-worker")
        storage.receive(mapper.writeValueAsString(interrupted))
        val leased = inbox.findAll().single { it.eventId == interrupted.eventId }
        leased.attempts = 1
        leased.retryAt = LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1)
        inbox.saveAndFlush(leased)
        storage.process(leased.id)
        assertNull(impressions.findByEventId(interrupted.eventId))
        leased.retryAt = LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1)
        inbox.saveAndFlush(leased)
        storage.process(leased.id)
        assertNotNull(impressions.findByEventId(interrupted.eventId))
        assertEquals("DONE", inbox.findById(leased.id).orElseThrow().status)
        assertEquals(2, inbox.findById(leased.id).orElseThrow().attempts)
    }
}
