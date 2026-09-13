package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentChangeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface ExperimentChangeRepository : JpaRepository<ExperimentChangeEntity, Long> {
    @org.springframework.data.jpa.repository.Query("SELECT COALESCE(MAX(c.id), 0) FROM ExperimentChangeEntity c")
    fun latestRevision(): Long
    fun findTop50ByExperimentIdOrderByIdDesc(experimentId: Long): List<ExperimentChangeEntity>
}
