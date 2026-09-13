package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ConversionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ImpressionLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.stereotype.Repository

@Repository
interface ImpressionLogRepository : JpaRepository<ImpressionLogEntity, Long> {
    fun findByEventId(eventId: String): ImpressionLogEntity?
    @Query("SELECT i.variant, COUNT(DISTINCT i.userId) FROM ImpressionLogEntity i WHERE i.experimentKey = :experimentKey GROUP BY i.variant")
    fun countImpressionsByVariant(experimentKey: String): List<Array<Any>>

    // Database insertion order is independent of API server clocks.
    fun findFirstByUserIdAndExperimentKeyOrderByIdDesc(userId: String, experimentKey: String): ImpressionLogEntity?

    // Buffered batches can arrive out of order. Event ID breaks timestamp ties independently of arrival order.
    fun findFirstByUserIdAndExperimentKeyOrderByTimestampDescEventIdDescIdDesc(userId: String, experimentKey: String): ImpressionLogEntity?
}

// New events reference the exposure validated when the event was accepted, regardless of clock skew.
// Historical rows have no reference; retain their conservative timestamp check.
// EXISTS avoids multiplying conversions by repeated exposures and validates attribution identities.
private const val ELIGIBLE_CONVERSION = """
    c.experimentKey = :experimentKey AND EXISTS (
        SELECT i.id FROM ImpressionLogEntity i
        WHERE i.experimentKey = c.experimentKey AND i.variant = c.variant
          AND i.userId = c.userId
          AND (i.id = c.impressionId OR (c.impressionId IS NULL AND i.timestamp <= c.timestamp)))
"""

@Repository
interface ConversionLogRepository : JpaRepository<ConversionLogEntity, Long> {
    @Query("SELECT c.variant, COUNT(DISTINCT c.userId) FROM ConversionLogEntity c WHERE " +
        ELIGIBLE_CONVERSION + " AND c.eventName = :eventName GROUP BY c.variant")
    fun countConversionsByVariant(experimentKey: String, eventName: String): List<Array<Any>>

    @Query("SELECT c.eventName, c.variant, COUNT(DISTINCT c.userId), COUNT(c) FROM ConversionLogEntity c WHERE " +
        ELIGIBLE_CONVERSION + " GROUP BY c.eventName, c.variant ORDER BY c.eventName, c.variant")
    fun countEventsByVariant(experimentKey: String): List<Array<Any>>
}
