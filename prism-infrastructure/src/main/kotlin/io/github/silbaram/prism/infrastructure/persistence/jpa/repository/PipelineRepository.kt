package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.LocalDateTime

interface PipelineInboxRepository : JpaRepository<PipelineInboxEntity, String> {
    fun countByStatus(status: String): Long
    @Query("SELECT p.id FROM PipelineInboxEntity p WHERE p.status = 'PENDING' AND p.retryAt <= :now ORDER BY p.retryAt, p.id")
    fun due(now: LocalDateTime, pageable: Pageable): List<String>
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT p FROM PipelineInboxEntity p WHERE p.id = :id")
    fun lock(id: String): PipelineInboxEntity?
}
interface PipelineOutboxRepository : JpaRepository<PipelineOutboxEntity, String> {
    @Query("SELECT p.id FROM PipelineOutboxEntity p ORDER BY p.id") fun pending(pageable: Pageable): List<String>
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT p FROM PipelineOutboxEntity p WHERE p.id = :id")
    fun lock(id: String): PipelineOutboxEntity?
}
