package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.EventDefinitionEntity
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface EventDefinitionRepository : JpaRepository<EventDefinitionEntity, Long> {
    fun existsByName(name: String): Boolean
    fun findByNameIn(names: Collection<String>): List<EventDefinitionEntity>

    @Query("SELECT e.name FROM EventDefinitionEntity e WHERE e.name LIKE :pattern ESCAPE '!' ORDER BY e.name")
    fun findNames(pattern: String, pageable: Pageable): List<String>
}
