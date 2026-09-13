package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.*

@Service
class AnalysisFinalizer(private val observations: AnalysisObservationRepository, private val experiments: ExperimentRepository,
                        private val conversions: ConversionLogRepository, manager: PlatformTransactionManager,
                        @param:Value("\${prism.analysis.finalization-enabled:true}") private val enabled: Boolean) {
    private val transaction = TransactionTemplate(manager)
    @Scheduled(fixedDelayString = "\${prism.analysis.finalization-interval-ms:30000}")
    fun tick() { if (enabled) finalizeDue() }

    fun finalizeDue(now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): Int {
        var count = 0
        observations.due(now, PageRequest.of(0, 500)).forEach { id ->
            transaction.executeWithoutResult {
                val row = observations.lock(id) ?: return@executeWithoutResult
                if (row.finalizedAt != null || row.maturesAt > now) return@executeWithoutResult
                val experiment = experiments.findById(row.experimentId).orElseThrow()
                row.converted = conversions.countWindowConversions(experiment.key, row.userId, row.variant,
                    requireNotNull(experiment.goalEventName), row.exposedAt, row.outcomeEndsAt) > 0
                row.finalizedAt = now
                count++
            }
        }
        return count
    }
}
