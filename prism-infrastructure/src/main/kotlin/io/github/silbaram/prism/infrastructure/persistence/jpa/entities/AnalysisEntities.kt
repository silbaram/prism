package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity @Table(name = "analysis_plans")
class AnalysisPlanEntity(
    @Id @Column(name = "experiment_id") val experimentId: Long,
    @Column(name = "control_variant", nullable = false) val controlVariant: String,
    @Column(name = "outcome_hours", nullable = false) val outcomeHours: Int,
    @Column(name = "lateness_hours", nullable = false) val latenessHours: Int,
    @Column(name = "segments_json", nullable = false, columnDefinition = "TEXT") val segmentsJson: String = "{}",
    @Column(name = "cuped_enabled", nullable = false) val cupedEnabled: Boolean = false,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "baseline_cutoff") val baselineCutoff: LocalDateTime? = null,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    @Column(name = "baseline_metric", length = 255) val baselineMetric: String? = null
)

@Entity @Table(name = "analysis_observations")
class AnalysisObservationEntity(
    @Id @Column(length = 64) val id: String,
    @Column(name = "experiment_id", nullable = false) val experimentId: Long,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) val variant: String,
    @Column(name = "exposure_id", nullable = false) var exposureId: Long,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "exposed_at", nullable = false) var exposedAt: LocalDateTime,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "outcome_ends_at", nullable = false) var outcomeEndsAt: LocalDateTime,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "matures_at", nullable = false) var maturesAt: LocalDateTime,
    @Column(name = "segments_json", nullable = false, columnDefinition = "TEXT") var segmentsJson: String = "{}",
    @Column(name = "baseline_value") var baselineValue: Double? = null,
    @Column(name = "invalid_reason", length = 64) var invalidReason: String? = null,
    @Column var converted: Boolean? = null,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "finalized_at") var finalizedAt: LocalDateTime? = null
)
