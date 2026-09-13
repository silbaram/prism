package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.*
import java.time.LocalDateTime

interface AnalysisPlanRepository : JpaRepository<AnalysisPlanEntity, Long>

interface AnalysisSample {
    val id: String
    val variant: String
    val converted: Boolean
    val baselineValue: Double?
    val segmentsJson: String
}

interface AnalysisObservationRepository : JpaRepository<AnalysisObservationEntity, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("SELECT o FROM AnalysisObservationEntity o WHERE o.id = :id")
    fun lock(id: String): AnalysisObservationEntity?
    @Query("SELECT o.id FROM AnalysisObservationEntity o WHERE o.finalizedAt IS NULL AND o.maturesAt <= :now ORDER BY o.maturesAt, o.id")
    fun due(now: LocalDateTime, pageable: Pageable): List<String>
    @Query("SELECT o.variant, COUNT(o) FROM AnalysisObservationEntity o WHERE o.experimentId = :id GROUP BY o.variant")
    fun enrollment(id: Long): List<Array<Any>>
    fun countByExperimentIdAndFinalizedAtIsNull(experimentId: Long): Long
    fun countByExperimentIdAndInvalidReasonIsNotNull(experimentId: Long): Long
    @Query("SELECT o.id AS id, o.variant AS variant, o.converted AS converted, o.baselineValue AS baselineValue, o.segmentsJson AS segmentsJson " +
        "FROM AnalysisObservationEntity o WHERE o.experimentId = :experimentId AND o.finalizedAt IS NOT NULL AND o.id > :after ORDER BY o.id")
    fun samples(experimentId: Long, after: String, pageable: Pageable): List<AnalysisSample>
}
