package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import io.github.silbaram.prism.core.model.HoldoutPolicy
import io.github.silbaram.prism.core.model.LayerAllocation

@Entity @Table(name = "population_policy")
class PopulationPolicyEntity(
    @Id val id: Long = 1,
    @Column(name = "holdout_key", nullable = false) var holdoutKey: String = "global-v1",
    @Column(name = "holdout_basis_points") var holdoutBasisPoints: Int? = null
) {
    fun toDomain() = HoldoutPolicy(holdoutKey, holdoutBasisPoints ?: 0)
}

@Entity @Table(name = "experiment_layers")
class ExperimentLayerEntity(
    @Id @Column(name = "layer_key") val key: String,
    @Column(nullable = false) val description: String = ""
)

fun ExperimentEntity.layerAllocation(): LayerAllocation? {
    if (layerKey == null) {
        require(layerStart == null && layerEnd == null) { "Layer range requires a layer" }
        return null
    }
    return LayerAllocation(requireNotNull(layerKey), requireNotNull(layerStart), requireNotNull(layerEnd))
}
