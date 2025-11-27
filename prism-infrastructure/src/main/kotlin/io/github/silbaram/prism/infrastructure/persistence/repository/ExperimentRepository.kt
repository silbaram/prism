package io.github.silbaram.prism.infrastructure.persistence.repository

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExperimentRepository : JpaRepository<ExperimentEntity, Long> {
    fun findByKey(key: String): ExperimentEntity?
    fun findAllByStatus(status: ExperimentStatus): List<ExperimentEntity>
}
