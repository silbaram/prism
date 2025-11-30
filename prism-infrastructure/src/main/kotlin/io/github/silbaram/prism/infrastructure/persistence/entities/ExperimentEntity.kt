package io.github.silbaram.prism.infrastructure.persistence.entities

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    var status: ExperimentStatus = ExperimentStatus.DRAFT,

    @OneToMany(mappedBy = "experiment", cascade = [CascadeType.ALL], orphanRemoval = true, fetch = FetchType.LAZY)
    var variants: MutableList<VariantEntity> = mutableListOf(),

    @Column(nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    var updatedAt: LocalDateTime = LocalDateTime.now()
) {
    fun addVariant(variant: VariantEntity) {
        variants.add(variant)
        variant.experiment = this
    }
}

enum class ExperimentStatus {
    DRAFT, ACTIVE, PAUSED, ENDED
}
