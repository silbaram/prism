package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.LocalDateTime

interface PipelineInboxRepository : JpaRepository<PipelineInboxEntity, String> {
    fun countByStatus(status: String): Long
    fun countByStatusAndRetryAtLessThanEqual(status: String, now: LocalDateTime): Long
    fun countByStatusAndAttemptsGreaterThan(status: String, attempts: Int): Long
    @Query("SELECT MIN(p.createdAt) FROM PipelineInboxEntity p WHERE p.status = 'PENDING'")
    fun oldestPendingAt(): LocalDateTime?
    @Query("SELECT p.id FROM PipelineInboxEntity p WHERE p.status = 'PENDING' AND p.retryAt <= :now ORDER BY p.retryAt, p.id")
    fun due(now: LocalDateTime, pageable: Pageable): List<String>
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT p FROM PipelineInboxEntity p WHERE p.id = :id")
    fun lock(id: String): PipelineInboxEntity?
}
interface PipelineOutboxRepository : JpaRepository<PipelineOutboxEntity, String> {
    fun countByKind(kind: String): Long
    fun countByCreatedAtIsNull(): Long
    @Query("SELECT MIN(p.createdAt) FROM PipelineOutboxEntity p")
    fun oldestPendingAt(): LocalDateTime?
    @Query("SELECT p.id FROM PipelineOutboxEntity p ORDER BY p.id") fun pending(pageable: Pageable): List<String>
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT p FROM PipelineOutboxEntity p WHERE p.id = :id")
    fun lock(id: String): PipelineOutboxEntity?
}
