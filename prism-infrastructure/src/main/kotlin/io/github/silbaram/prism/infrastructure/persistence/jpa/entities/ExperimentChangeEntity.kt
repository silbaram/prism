package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

/** No foreign key: deleting an unused draft must not delete its audit history. */
@Entity
@Table(name = "experiment_changes", indexes = [Index(name = "idx_experiment_changes", columnList = "experiment_id,id")])
class ExperimentChangeEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    @Column(name = "experiment_id", nullable = false)
    val experimentId: Long,
    @Column(name = "experiment_key", nullable = false)
    val experimentKey: String,
    @Column(nullable = false, length = 32)
    val action: String,
    @Column(name = "before_snapshot", columnDefinition = "LONGTEXT")
    val beforeSnapshot: String?,
    @Column(name = "after_snapshot", columnDefinition = "LONGTEXT")
    val afterSnapshot: String?,
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "changed_at", nullable = false)
    val changedAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
)
