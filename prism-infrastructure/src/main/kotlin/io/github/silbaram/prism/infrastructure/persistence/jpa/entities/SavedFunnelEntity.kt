package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import org.hibernate.annotations.OnDelete
import org.hibernate.annotations.OnDeleteAction
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Reusable analysis criteria; saving a funnel never changes experiment assignment or events. */
@Entity
@Table(name = "saved_funnels",
    uniqueConstraints = [UniqueConstraint(name = "uk_saved_funnel_name", columnNames = ["experiment_id", "name"])],
    indexes = [Index(name = "idx_saved_funnel_experiment", columnList = "experiment_id,id")])
class SavedFunnelEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "experiment_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    val experiment: ExperimentEntity,
    @Column(nullable = false, length = 120)
    var name: String,
    @Column(nullable = false, length = 1000)
    var description: String = "",
    @Column(name = "steps_json", nullable = false, columnDefinition = "LONGTEXT")
    var stepsJson: String,
    @Column(name = "window_hours", nullable = false)
    var windowHours: Int,
    @Column(name = "period_mode", nullable = false, length = 16)
    var periodMode: String,
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "from_at", columnDefinition = "DATETIME(6)")
    var fromAt: LocalDateTime? = null,
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "until_at", columnDefinition = "DATETIME(6)")
    var untilAt: LocalDateTime? = null,
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = createdAt,
    @Version
    @Column(nullable = false)
    var version: Long? = null
)
