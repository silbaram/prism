package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ImpressionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ConversionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.sqrt
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import org.springframework.transaction.annotation.Isolation
import java.time.LocalDateTime
import io.github.silbaram.prism.admin.exception.requireValidInput

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class AnalyticsService(
    private val impressionRepository: ImpressionLogRepository,
    private val conversionRepository: ConversionLogRepository,
    private val experimentRepository: ExperimentRepository
) {
    fun getExperimentStats(experimentKey: String): ExperimentStats {
        val experiment = requireNotNull(experimentRepository.findByKey(experimentKey)) { "Experiment not found: $experimentKey" }
        val goal = experiment.goalEventName?.takeIf { it.isNotBlank() }
        val impressions = impressionRepository.countImpressionsByVariant(experimentKey)
            .associate { (it[0] as String) to (it[1] as Long) }
        val conversions = goal?.let { event ->
            conversionRepository.countConversionsByVariant(experimentKey, event)
                .associate { (it[0] as String) to (it[1] as Long) }
        }.orEmpty()
        val variants = (experiment.variants.map { it.name } + impressions.keys + conversions.keys).distinct()
        val stats = variants.map { variant ->
            val exposed = impressions[variant] ?: 0L
            val converted = if (goal == null) null else conversions[variant] ?: 0L
            VariantStats(variant, exposed, converted,
                if (exposed > 0 && converted != null) converted.toDouble() / exposed * 100 else null,
                if (exposed > 0 && converted != null) wilsonInterval(converted, exposed) else null)
        }
        val srm = sampleRatioMismatch(experiment.variants.map { it.name to it.weight }, impressions,
            impressionRepository.countExposedUsers(experimentKey))
        val participatingVariants = experiment.variants.filter { it.weight > 0 }.map { it.name }.toSet()
        return ExperimentStats(experimentKey, goal, stats, srm,
            compareConversionRates(stats.filter { it.variant in participatingVariants }, srm,
                experiment.status == ExperimentStatus.ENDED))
    }

    fun getEventStats(experimentKey: String, eventName: String? = null): List<EventStats> {
        requireValidInput(eventName == null || (eventName.isNotBlank() && eventName.length <= 255)) { "이벤트 이름은 1–255자여야 합니다." }
        val experiment = requireNotNull(experimentRepository.findByKey(experimentKey))
        val exposed = impressionRepository.countImpressionsByVariant(experimentKey).associate { (it[0] as String) to (it[1] as Long) }
        val observed = conversionRepository.eventMetrics(experimentKey, eventName)
        val indexed = observed.associateBy { it.eventName to it.variant }
        val names = if (eventName != null) listOf(eventName) else
            (listOfNotNull(experiment.goalEventName?.takeIf(String::isNotBlank)) + experiment.guardrailEventNames + observed.map { it.eventName }).distinct().sorted()
        val variants = (experiment.variants.map { it.name } + exposed.keys + observed.map { it.variant }).distinct()
        return names.flatMap { name ->
            val kind = when (name) {
                experiment.goalEventName -> "PRIMARY"
                in experiment.guardrailEventNames -> "GUARDRAIL"
                else -> "SECONDARY"
            }
            variants.map { variant ->
                val metric = indexed[name to variant]
                val denominator = exposed[variant] ?: 0
                val users = metric?.users ?: 0
                EventStats(name, variant, users, metric?.events ?: 0, kind, denominator,
                    if (denominator > 0) users.toDouble() / denominator * 100 else null, metric?.lastOccurredAt)
            }
        }
    }
}

/** Descriptive 95% Wilson interval for a user-level binomial proportion, in percent.
 * https://www.itl.nist.gov/div898/handbook/prc/section2/prc241.htm
 * This interval is not a test of differences between variants.
 */
internal fun wilsonInterval(successes: Long, trials: Long): ConfidenceInterval {
    require(trials > 0 && successes in 0..trials)
    val n = trials.toDouble()
    val p = successes / n
    val z = 1.959963984540054
    val z2 = z * z
    val denominator = 1 + z2 / n
    val center = (p + z2 / (2 * n)) / denominator
    val margin = z * sqrt(p * (1 - p) / n + z2 / (4 * n * n)) / denominator
    return ConfidenceInterval((center - margin).coerceAtLeast(0.0) * 100,
        (center + margin).coerceAtMost(1.0) * 100)
}

data class ExperimentStats(val experimentKey: String, val goalEventName: String?, val stats: List<VariantStats>,
    val srm: SrmResult, val comparison: GroupComparison)
data class VariantStats(val variant: String, val impressions: Long, val conversions: Long?,
    val cvr: Double?, val confidenceInterval: ConfidenceInterval?)
data class ConfidenceInterval(val lower: Double, val upper: Double)
data class EventStats(val eventName: String, val variant: String, val users: Long, val events: Long, val kind: String,
                      val exposedUsers: Long, val cvr: Double?, val lastOccurredAt: LocalDateTime?)
