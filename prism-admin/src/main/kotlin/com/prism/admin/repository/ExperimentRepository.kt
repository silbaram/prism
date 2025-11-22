
package com.prism.admin.repository

import com.prism.admin.domain.ExperimentEntity
import com.prism.admin.domain.ExperimentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExperimentRepository : JpaRepository<ExperimentEntity, Long> {
    fun findByKey(key: String): ExperimentEntity?
    fun findAllByStatus(status: ExperimentStatus): List<ExperimentEntity>
}
