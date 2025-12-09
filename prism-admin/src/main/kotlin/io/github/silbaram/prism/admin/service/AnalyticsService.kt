package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ImpressionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ConversionLogRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AnalyticsService(
    private val impressionRepository: ImpressionLogRepository,
    private val conversionRepository: ConversionLogRepository
) {
    fun getExperimentStats(experimentKey: String): ExperimentStats {
        val impressions = impressionRepository.countImpressionsByVariant(experimentKey)
            .associate { (it[0] as String) to (it[1] as Long) }

        val conversions = conversionRepository.countConversionsByVariant(experimentKey)
            .associate { (it[0] as String) to (it[1] as Long) }

        // impressions와 conversions의 모든 variant를 수집
        val allVariants = (impressions.keys + conversions.keys).distinct()

        val variantStats = allVariants.map { variant ->
            val impressionCount = impressions[variant] ?: 0L
            val conversionCount = conversions[variant] ?: 0L
            val cvr = if (impressionCount > 0) (conversionCount.toDouble() / impressionCount) * 100 else 0.0

            VariantStats(
                variant = variant,
                impressions = impressionCount,
                conversions = conversionCount,
                cvr = cvr
            )
        }

        val winner = variantStats.maxByOrNull { it.cvr }?.takeIf { it.impressions > 10 } // Minimal sample size check

        return ExperimentStats(
            experimentKey = experimentKey,
            stats = variantStats,
            winnerVariant = winner?.variant
        )
    }
}

data class ExperimentStats(
    val experimentKey: String,
    val stats: List<VariantStats>,
    val winnerVariant: String?
)

data class VariantStats(
    val variant: String,
    val impressions: Long,
    val conversions: Long,
    val cvr: Double
)
