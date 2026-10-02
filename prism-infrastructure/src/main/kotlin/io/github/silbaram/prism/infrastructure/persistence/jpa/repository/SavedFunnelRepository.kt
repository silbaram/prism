package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.SavedFunnelEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface SavedFunnelRepository : JpaRepository<SavedFunnelEntity, Long> {
    fun findByIdAndExperimentId(id: Long, experimentId: Long): SavedFunnelEntity?
    fun findByExperimentIdOrderByIdAsc(experimentId: Long, pageable: Pageable): List<SavedFunnelEntity>
    fun findByExperimentIdAndIdGreaterThanOrderByIdAsc(experimentId: Long, afterId: Long, pageable: Pageable): List<SavedFunnelEntity>
    fun existsByExperimentIdAndName(experimentId: Long, name: String): Boolean
    fun existsByExperimentIdAndNameAndIdNot(experimentId: Long, name: String, id: Long): Boolean
}
