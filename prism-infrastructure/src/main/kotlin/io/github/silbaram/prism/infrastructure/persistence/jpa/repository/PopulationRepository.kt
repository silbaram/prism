package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.*

interface PopulationPolicyRepository : JpaRepository<PopulationPolicyEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PopulationPolicyEntity p WHERE p.id = 1")
    fun lockPolicy(): PopulationPolicyEntity?
}
interface ExperimentLayerRepository : JpaRepository<ExperimentLayerEntity, String>
