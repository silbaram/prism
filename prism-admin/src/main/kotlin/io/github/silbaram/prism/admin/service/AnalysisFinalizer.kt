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
    private val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
    @Scheduled(fixedDelayString = "\${prism.analysis.finalization-interval-ms:30000}")
    fun tick() { if (enabled) finalizeDue() }

    fun finalizeDue(now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)): Int {
        var count = 0
        observations.due(now, PageRequest.of(0, 500)).forEach { id ->
            try {
                val finalized = transaction.execute {
                    val row = observations.lock(id) ?: return@execute false
                    if (row.finalizedAt != null || row.maturesAt > now || row.finalizationRetryAt?.let { it > now } == true) return@execute false
                    val experiment = experiments.findById(row.experimentId).orElseThrow()
                    row.converted = conversions.countWindowConversions(experiment.key, row.userId, row.variant,
                        requireNotNull(experiment.goalEventName), row.exposedAt, row.outcomeEndsAt) > 0
                    row.finalizedAt = now
                    row.finalizationRetryAt = null
                    row.finalizationAttempts = 0
                    true
                }
                if (finalized == true) count++ // Count only committed results.
            } catch (error: RuntimeException) {
                if (Thread.currentThread().isInterrupted) throw error
                logger.warn("Analysis finalization failed: observation={}, cause={}", id, error.javaClass.simpleName)
                // Back off durably so 500 broken rows cannot permanently starve later healthy rows.
                try {
                    transaction.executeWithoutResult {
                        val row = observations.lock(id) ?: return@executeWithoutResult
                        if (row.finalizedAt != null || row.finalizationRetryAt?.let { it > now } == true) return@executeWithoutResult
                        row.finalizationAttempts = (row.finalizationAttempts + 1).coerceAtMost(8)
                        // Earlier rows may have taken longer than the retry delay since the batch cutoff.
                        val retryFrom = maxOf(now, LocalDateTime.now(ZoneOffset.UTC))
                        row.finalizationRetryAt = retryFrom.plusSeconds(minOf(3600L, 30L shl (row.finalizationAttempts - 1)))
                    }
                } catch (retryError: RuntimeException) {
                    if (Thread.currentThread().isInterrupted) throw retryError
                    logger.warn("Unable to persist analysis retry: observation={}, cause={}", id, retryError.javaClass.simpleName)
                }
            }
        }
        return count
    }
}
