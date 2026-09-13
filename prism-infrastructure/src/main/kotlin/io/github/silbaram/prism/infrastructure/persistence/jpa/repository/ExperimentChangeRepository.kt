package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentChangeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ExperimentChangeRepository : JpaRepository<ExperimentChangeEntity, Long> {
    fun findTop50ByExperimentIdOrderByIdDesc(experimentId: Long): List<ExperimentChangeEntity>
}
