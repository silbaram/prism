
package com.prism.api.domain

import jakarta.persistence.*

@Entity
@Table(name = "experiments")
class ExperimentEntity(
    @Id
    val id: Long? = null,

    @Column(nullable = false, unique = true)
    val key: String,

    @Column(nullable = false)
    val status: String, // String for simplicity in read-only

    @OneToMany(mappedBy = "experiment", fetch = FetchType.EAGER)
    val variants: List<VariantEntity> = emptyList()
)

@Entity
@Table(name = "variants")
class VariantEntity(
    @Id
    val id: Long? = null,

    @Column(nullable = false)
    val name: String,

    @Column(nullable = false)
    val weight: Int,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "experiment_id")
    val experiment: ExperimentEntity? = null
)
