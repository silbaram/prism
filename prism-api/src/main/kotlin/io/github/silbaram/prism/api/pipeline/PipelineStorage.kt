package io.github.silbaram.prism.api.pipeline

import com.fasterxml.jackson.module.kotlin.*
import io.github.silbaram.prism.api.event.EventIngestionService
import io.github.silbaram.prism.common.rest.dto.event.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import jakarta.persistence.EntityManager
import org.springframework.stereotype.Service
import org.springframework.transaction.*
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class PipelineStorage(manager: PlatformTransactionManager, private val entityManager: EntityManager,
                      private val inbox: PipelineInboxRepository, private val outbox: PipelineOutboxRepository,
                      private val ingestion: EventIngestionService) {
    private val transaction = TransactionTemplate(manager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }
    private val mapper = jacksonObjectMapper().enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    fun receive(payload: String, source: String? = null) {
        val event = try { mapper.readValue<ClientEvent>(payload) } catch (_: Exception) {
            transaction.executeWithoutResult { reject(payload, "Malformed pipeline event", source) }
            return
        }
        val canonical = mapper.writeValueAsString(event)
        try { ingestion.validate(event) } catch (error: IllegalArgumentException) {
            transaction.executeWithoutResult { reject(canonical, error.message ?: "Invalid pipeline event", source) }
            return
        }
        val id = hash(canonical)
        try {
            transaction.executeWithoutResult {
                if (!inbox.existsById(id)) { entityManager.persist(PipelineInboxEntity(id, event.eventId, canonical)); entityManager.flush() }
            }
        } catch (error: RuntimeException) { if (!inbox.existsById(id)) throw error }
    }

    fun process(id: String) {
        // Commit a short lease before materialization. Never hold an inbox connection while
        // ingestion opens its transaction; a one-connection pool must also make progress.
        val claim = transaction.execute {
            val item = inbox.lock(id) ?: return@execute null
            val now = LocalDateTime.now(ZoneOffset.UTC)
            if (item.status != "PENDING" || item.retryAt > now) return@execute null
            item.attempts++
            item.retryAt = now.plusMinutes(1)
            item.payload to item.attempts
        } ?: return
        val result = ingestion.ingest(listOf(mapper.readValue<ClientEvent>(claim.first))).results.single()
        transaction.executeWithoutResult {
            val item = inbox.lock(id) ?: return@executeWithoutResult
            // An expired lease may have been reclaimed while ingestion was blocked.
            if (item.status != "PENDING" || item.attempts != claim.second) return@executeWithoutResult
            val now = LocalDateTime.now(ZoneOffset.UTC)
            when (result.status) {
                EventStatus.ACCEPTED, EventStatus.DUPLICATE -> item.status = "DONE"
                EventStatus.REJECTED -> { reject(item.payload, result.message ?: "Rejected"); item.status = "REJECTED" }
                EventStatus.RETRY -> {
                    if (now > item.createdAt.plusDays(7)) { reject(item.payload, "Retry window expired"); item.status = "REJECTED" }
                    else item.retryAt = now.plusSeconds(item.attempts.toLong().coerceAtMost(60))
                }
                EventStatus.QUEUED -> error("Materializer cannot enqueue recursively")
            }
        }
    }

    private fun reject(payload: String, reason: String, source: String? = null) {
        val id = hash("rejected:$payload")
        if (!outbox.existsById(id)) entityManager.persist(PipelineOutboxEntity(id, "DEAD_LETTER",
            mapper.writeValueAsString(mapOf("payload" to payload.take(65_536), "reason" to reason.take(1024),
                "truncated" to (payload.length > 65_536), "payloadHash" to hash(payload), "source" to source))))
    }

    fun publish(id: String, send: (PipelineOutboxEntity) -> Unit) = transaction.executeWithoutResult {
        val item = outbox.lock(id) ?: return@executeWithoutResult
        send(item) // Commit/delete only after the destination acknowledges durable receipt.
        outbox.delete(item)
    }
}
