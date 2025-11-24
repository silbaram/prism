package io.github.silbaram.prism.admin.api.repository

import io.github.silbaram.prism.admin.api.domain.ImpressionLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface AnalyticsRepository : JpaRepository<ImpressionLogEntity, Long> {

    @Query("SELECT i.variant, COUNT(i) FROM ImpressionLogEntity i WHERE i.experimentKey = :experimentKey GROUP BY i.variant")
    fun countImpressionsByVariant(experimentKey: String): List<Array<Any>>

    @Query("SELECT i.variant, COUNT(c) FROM ImpressionLogEntity i LEFT JOIN ConversionLogEntity c ON i.userId = c.userId AND i.experimentKey = c.experimentKey WHERE i.experimentKey = :experimentKey GROUP BY i.variant")
    fun countConversionsByVariant(experimentKey: String): List<Array<Any>>
}
