package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import jakarta.persistence.LockModeType
import org.springframework.stereotype.Repository

@Repository
interface ExperimentRepository : JpaRepository<ExperimentEntity, Long> {
    @Query("SELECT e.id FROM ExperimentEntity e WHERE (e.status = 'SCHEDULED' AND e.startsAt <= :now) OR " +
        "(e.status IN ('SCHEDULED', 'ACTIVE', 'PAUSED') AND e.endsAt <= :now)")
    fun findScheduleCandidates(now: java.time.LocalDateTime): List<Long>
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM ExperimentEntity e WHERE e.id = :id")
    fun findForUpdate(id: Long): ExperimentEntity?
    fun findByKey(key: String): ExperimentEntity?
    fun findAllByStatus(status: ExperimentStatus): List<ExperimentEntity>

    // 페이지네이션 및 검색을 위한 메서드
    fun findByKeyContaining(key: String, pageable: Pageable): Page<ExperimentEntity>
    fun findByStatus(status: ExperimentStatus, pageable: Pageable): Page<ExperimentEntity>
    fun findByKeyContainingAndStatus(key: String, status: ExperimentStatus, pageable: Pageable): Page<ExperimentEntity>
}
