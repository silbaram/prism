package io.github.silbaram.prism.infrastructure.persistence.repository

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExperimentRepository : JpaRepository<ExperimentEntity, Long> {
    fun findByKey(key: String): ExperimentEntity?
    fun findAllByStatus(status: ExperimentStatus): List<ExperimentEntity>

    // 페이지네이션 및 검색을 위한 메서드
    fun findByKeyContaining(key: String, pageable: Pageable): Page<ExperimentEntity>
    fun findByStatus(status: ExperimentStatus, pageable: Pageable): Page<ExperimentEntity>
    fun findByKeyContainingAndStatus(key: String, status: ExperimentStatus, pageable: Pageable): Page<ExperimentEntity>
}
