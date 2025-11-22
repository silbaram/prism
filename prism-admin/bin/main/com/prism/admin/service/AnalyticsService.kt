
package com.prism.admin.service

import com.prism.admin.repository.AnalyticsRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AnalyticsService(
    private val analyticsRepository: AnalyticsRepository
) {
    fun getExperimentStats(experimentKey: String): ExperimentStats {
        val impressions = analyticsRepository.countImpressionsByVariant(experimentKey)
            .associate { (it[0] as String) to (it[1] as Long) }
        
        val conversions = analyticsRepository.countConversionsByVariant(experimentKey)
            .associate { (it[0] as String) to (it[1] as Long) }

        val variantStats = impressions.map { (variant, impressionCount) ->
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
