
package com.prism.api.repository

import com.prism.api.domain.ExperimentEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExperimentRepository : JpaRepository<ExperimentEntity, Long> {
    fun findByKey(key: String): ExperimentEntity?
    fun findAllByStatus(status: String): List<ExperimentEntity>
}
