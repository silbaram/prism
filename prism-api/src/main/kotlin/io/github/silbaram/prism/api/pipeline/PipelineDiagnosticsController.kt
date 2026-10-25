package io.github.silbaram.prism.api.pipeline

import io.github.silbaram.prism.common.rest.dto.diagnostics.*
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.PipelineInboxRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.PipelineOutboxRepository
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.*

@Service
class PipelineDiagnosticsService(
    private val properties: PipelineProperties,
    private val inbox: PipelineInboxRepository,
    private val outbox: PipelineOutboxRepository
) {
    @Transactional(readOnly = true)
    fun snapshot(): PipelineDiagnostics {
        val now = Instant.now()
        fun age(value: LocalDateTime?) = value?.let { Duration.between(it.toInstant(ZoneOffset.UTC), now).seconds.coerceAtLeast(0) }
        return PipelineDiagnostics(now, properties.mode.name,
            properties.mode == PipelineMode.KAFKA && properties.consumerEnabled,
            InboxDiagnostics(inbox.countByStatus("PENDING"),
                inbox.countByStatusAndRetryAtLessThanEqual("PENDING", LocalDateTime.ofInstant(now, ZoneOffset.UTC)),
                inbox.countByStatusAndAttemptsGreaterThan("PENDING", 0), inbox.countByStatus("DONE"),
                inbox.countByStatus("REJECTED"), age(inbox.oldestPendingAt())),
            OutboxDiagnostics(outbox.count(), outbox.countByKind("WAREHOUSE"), outbox.countByKind("DEAD_LETTER"),
                age(outbox.oldestPendingAt()), outbox.countByCreatedAtIsNull()))
    }
}

@RestController
class PipelineDiagnosticsController(private val diagnostics: PipelineDiagnosticsService) {
    @GetMapping("/api/internal/pipeline/diagnostics")
    fun snapshot(): ResponseEntity<PipelineDiagnostics> = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(diagnostics.snapshot())
}
