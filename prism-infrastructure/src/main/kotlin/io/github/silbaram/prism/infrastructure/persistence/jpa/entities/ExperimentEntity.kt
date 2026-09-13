package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "experiments")
class ExperimentEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "experiment_key", nullable = false, unique = true)
    var key: String,

    @Column(nullable = false)
    var description: String,

    @Column(name = "goal_event_name")
    var goalEventName: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ExperimentStatus = ExperimentStatus.DRAFT,

    @OneToMany(mappedBy = "experiment", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    var variants: MutableList<VariantEntity> = mutableListOf(),

    @OneToMany(mappedBy = "experiment", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id ASC")
    var targetingRules: MutableList<TargetingRuleEntity> = mutableListOf(),

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "configuration_locked", nullable = false)
    var configurationLocked: Boolean = false,
    @Column(name = "traffic_allocation", nullable = false)
    var trafficAllocation: Int = 100,
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "starts_at")
    var startsAt: LocalDateTime? = null,
    @Convert(converter = UtcLogTimestampConverter::class)
    @Column(name = "ends_at")
    var endsAt: LocalDateTime? = null,
    @ElementCollection
    @CollectionTable(name = "experiment_guardrails", joinColumns = [JoinColumn(name = "experiment_id")])
    @Column(name = "event_name", nullable = false)
    var guardrailEventNames: MutableSet<String> = linkedSetOf()
) {
    fun addVariant(variant: VariantEntity) {
        variants.add(variant)
        variant.experiment = this
    }

    fun addTargetingRule(rule: TargetingRuleEntity) {
        targetingRules.add(rule)
        rule.experiment = this
    }
}

enum class ExperimentStatus {
    DRAFT, SCHEDULED, ACTIVE, PAUSED, ENDED
}
