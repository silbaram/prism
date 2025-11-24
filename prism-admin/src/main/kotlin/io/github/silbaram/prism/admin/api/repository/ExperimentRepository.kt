package io.github.silbaram.prism.admin.api.repository

import io.github.silbaram.prism.admin.api.domain.ExperimentEntity
import io.github.silbaram.prism.admin.api.domain.ExperimentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExperimentRepository : JpaRepository<ExperimentEntity, Long> {
    fun findByKey(key: String): ExperimentEntity?
    fun findAllByStatus(status: ExperimentStatus): List<ExperimentEntity>
}
