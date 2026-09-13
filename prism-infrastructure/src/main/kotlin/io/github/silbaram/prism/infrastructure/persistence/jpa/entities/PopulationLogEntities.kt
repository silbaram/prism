package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity @Table(name = "population_exposures")
class PopulationExposureEntity(
    @Id @Column(name = "event_id", length = 36) val eventId: String,
    @Column(name = "cohort_key", nullable = false) val cohortKey: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false, length = 16) val variant: String,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "occurred_at", nullable = false) val occurredAt: LocalDateTime
)
@Entity @Table(name = "population_conversions")
class PopulationConversionEntity(
    @Id @Column(name = "event_id", length = 36) val eventId: String,
    @Column(name = "cohort_key", nullable = false) val cohortKey: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false, length = 16) val variant: String,
    @Column(name = "event_name", nullable = false) val eventName: String,
    @Column(name = "exposure_event_id", nullable = false, length = 36) val exposureEventId: String,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "occurred_at", nullable = false) val occurredAt: LocalDateTime
)
