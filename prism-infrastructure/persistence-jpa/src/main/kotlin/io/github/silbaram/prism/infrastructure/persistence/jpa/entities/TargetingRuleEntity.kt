package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*

@Entity
@Table(name = "targeting_rules")
class TargetingRuleEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "rule_expression", nullable = false, columnDefinition = "TEXT")
    var expression: String, // SpEL expression, e.g., "age >= 20 && country == 'KR'"

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id")
    var experiment: ExperimentEntity? = null
)
