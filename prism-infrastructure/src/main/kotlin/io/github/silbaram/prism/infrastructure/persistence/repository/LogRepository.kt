package io.github.silbaram.prism.infrastructure.persistence.repository

import io.github.silbaram.prism.infrastructure.persistence.entities.ConversionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ImpressionLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ImpressionLogRepository : JpaRepository<ImpressionLogEntity, Long> {
    @Query("SELECT i.variant, COUNT(i) FROM ImpressionLogEntity i WHERE i.experimentKey = :experimentKey GROUP BY i.variant")
    fun countImpressionsByVariant(experimentKey: String): List<Array<Any>>
}

@Repository
interface ConversionLogRepository : JpaRepository<ConversionLogEntity, Long> {
    @Query("SELECT i.variant, COUNT(c) FROM ImpressionLogEntity i LEFT JOIN ConversionLogEntity c ON i.userId = c.userId AND i.experimentKey = c.experimentKey WHERE i.experimentKey = :experimentKey GROUP BY i.variant")
    fun countConversionsByVariant(experimentKey: String): List<Array<Any>>
}
