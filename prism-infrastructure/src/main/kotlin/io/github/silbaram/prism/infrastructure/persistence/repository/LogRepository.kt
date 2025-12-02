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

    fun findFirstByUserIdAndExperimentKeyOrderByTimestampDesc(userId: String, experimentKey: String): ImpressionLogEntity?
}

@Repository
interface ConversionLogRepository : JpaRepository<ConversionLogEntity, Long> {
    @Query("SELECT c.variant, COUNT(c) FROM ConversionLogEntity c WHERE c.experimentKey = :experimentKey AND c.variant IS NOT NULL GROUP BY c.variant")
    fun countConversionsByVariant(experimentKey: String): List<Array<Any>>
}
