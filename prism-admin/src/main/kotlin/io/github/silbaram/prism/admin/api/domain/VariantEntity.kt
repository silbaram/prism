package io.github.silbaram.prism.admin.api.domain

import jakarta.persistence.*

@Entity
@Table(name = "variants")
class VariantEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    var name: String,

    @Column(nullable = false)
    var weight: Int,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id")
    var experiment: ExperimentEntity? = null
)
