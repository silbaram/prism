package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.*
import org.springframework.data.jpa.repository.*

interface PopulationExposureRepository : JpaRepository<PopulationExposureEntity, String> {
    @Query("SELECT p.variant, COUNT(DISTINCT p.userId) FROM PopulationExposureEntity p WHERE p.cohortKey = :key GROUP BY p.variant")
    fun users(key: String): List<Array<Any>>
}
interface PopulationConversionRepository : JpaRepository<PopulationConversionEntity, String> {
    @Query("SELECT c.eventName, c.variant, COUNT(DISTINCT c.userId) FROM PopulationConversionEntity c " +
        "JOIN PopulationExposureEntity p ON p.eventId = c.exposureEventId WHERE c.cohortKey = :key AND c.occurredAt >= p.occurredAt " +
        "GROUP BY c.eventName, c.variant")
    fun outcomes(key: String): List<Array<Any>>
}
