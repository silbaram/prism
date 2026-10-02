package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ConversionLogEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ImpressionLogEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

@Repository
interface ImpressionLogRepository : JpaRepository<ImpressionLogEntity, Long> {
    fun existsByExperimentKey(experimentKey: String): Boolean
    @Query("SELECT COUNT(DISTINCT i.userId) FROM ImpressionLogEntity i WHERE i.experimentKey = :experimentKey")
    fun countExposedUsers(experimentKey: String): Long
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
internal const val ATTRIBUTED_CONVERSION = """
    EXISTS (
        SELECT i.id FROM ImpressionLogEntity i
        WHERE i.experimentKey = c.experimentKey AND i.variant = c.variant
          AND i.userId = c.userId
          AND (i.id = c.impressionId OR (c.impressionId IS NULL AND i.timestamp <= c.timestamp)))
"""
private const val ELIGIBLE_CONVERSION = "c.experimentKey = :experimentKey AND " + ATTRIBUTED_CONVERSION

@Repository
interface ConversionLogRepository : JpaRepository<ConversionLogEntity, Long> {
    @Query("SELECT DISTINCT c.eventName FROM ConversionLogEntity c WHERE " + ATTRIBUTED_CONVERSION +
        " AND c.eventName LIKE :pattern ESCAPE '!' ORDER BY c.eventName")
    fun findEventNames(pattern: String, pageable: Pageable): List<String>

    @Query("SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.EventObservationRow(" +
        "c.eventName, COUNT(c), COUNT(DISTINCT c.experimentKey), MAX(c.timestamp)) FROM ConversionLogEntity c WHERE " +
        ATTRIBUTED_CONVERSION + " AND c.eventName IN :names GROUP BY c.eventName")
    fun observeEventNames(names: Collection<String>): List<EventObservationRow>

    @Query("SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.EventMetricRow(" +
        "c.eventName, c.variant, COUNT(DISTINCT c.userId), COUNT(c), MAX(c.timestamp)) FROM ConversionLogEntity c WHERE " +
        ELIGIBLE_CONVERSION + " AND (:eventName IS NULL OR c.eventName = :eventName) GROUP BY c.eventName, c.variant ORDER BY c.eventName, c.variant")
    fun eventMetrics(experimentKey: String, eventName: String?): List<EventMetricRow>

    // Read projections with a keyset cursor: never retain every user's event history in memory.
    @Query("SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.FunnelEventRow(" +
        "c.id, c.userId, c.variant, c.eventName, c.timestamp) FROM ConversionLogEntity c WHERE " + ELIGIBLE_CONVERSION +
        " AND c.eventName IN :eventNames AND c.timestamp >= :from AND c.timestamp < :until" +
        " AND (:userId IS NULL OR c.userId = :userId) AND (:variant IS NULL OR c.variant = :variant)" +
        " AND (:afterUser IS NULL OR c.userId > :afterUser)" +
        " AND (:afterId = 0 OR c.userId > :afterUserId" +
        " OR (c.userId = :afterUserId AND c.variant > :afterVariant)" +
        " OR (c.userId = :afterUserId AND c.variant = :afterVariant AND c.timestamp > :afterTimestamp)" +
        " OR (c.userId = :afterUserId AND c.variant = :afterVariant AND c.timestamp = :afterTimestamp AND c.id > :afterId))" +
        " ORDER BY c.userId, c.variant, c.timestamp, c.id")
    fun findFunnelEvents(experimentKey: String, eventNames: List<String>, from: LocalDateTime, until: LocalDateTime,
                        afterUserId: String, afterVariant: String, afterTimestamp: LocalDateTime, afterId: Long,
                        pageable: Pageable, userId: String? = null, variant: String? = null,
                        afterUser: String? = null): List<FunnelEventRow>

    @Query("SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.JourneyPatternEventRow(" +
        "c.id, c.userId, c.variant, c.eventName, c.timestamp, " +
        "CASE WHEN :afterUser IS NULL OR c.userId > :afterUser THEN true ELSE false END) FROM ConversionLogEntity c WHERE " + ELIGIBLE_CONVERSION +
        " AND c.timestamp >= :from AND c.timestamp < :until AND (:variant IS NULL OR c.variant = :variant)" +
        " AND (:afterId = 0 OR c.userId > :afterUserId" +
        " OR (c.userId = :afterUserId AND c.variant > :afterVariant)" +
        " OR (c.userId = :afterUserId AND c.variant = :afterVariant AND c.timestamp > :afterTimestamp)" +
        " OR (c.userId = :afterUserId AND c.variant = :afterVariant AND c.timestamp = :afterTimestamp AND c.id > :afterId))" +
        " ORDER BY c.userId, c.variant, c.timestamp, c.id")
    fun findJourneyPatternEvents(experimentKey: String, from: LocalDateTime, until: LocalDateTime, variant: String?,
        afterUserId: String, afterVariant: String, afterTimestamp: LocalDateTime, afterId: Long,
        pageable: Pageable, afterUser: String? = null): List<JourneyPatternEventRow>

    @Query("SELECT COUNT(c) FROM ConversionLogEntity c WHERE " + ELIGIBLE_CONVERSION +
        " AND c.userId = :userId AND c.variant = :variant AND c.eventName = :eventName AND c.timestamp >= :start AND c.timestamp < :end")
    fun countWindowConversions(experimentKey: String, userId: String, variant: String, eventName: String,
        start: java.time.LocalDateTime, end: java.time.LocalDateTime): Long

    @Query("SELECT c.variant, COUNT(DISTINCT c.userId) FROM ConversionLogEntity c WHERE " +
        ELIGIBLE_CONVERSION + " AND c.eventName = :eventName GROUP BY c.variant")
    fun countConversionsByVariant(experimentKey: String, eventName: String): List<Array<Any>>

    @Query("SELECT c.eventName, c.variant, COUNT(DISTINCT c.userId), COUNT(c) FROM ConversionLogEntity c WHERE " +
        ELIGIBLE_CONVERSION + " GROUP BY c.eventName, c.variant ORDER BY c.eventName, c.variant")
    fun countEventsByVariant(experimentKey: String): List<Array<Any>>
}

data class FunnelEventRow(val id: Long, val userId: String, val variant: String, val eventName: String,
                          val timestamp: LocalDateTime)

/** Preserve the database's exact identity ordering when classifying a complete history for a user page. */
data class JourneyPatternEventRow(val id: Long, val userId: String, val variant: String, val eventName: String,
    val timestamp: LocalDateTime, val afterCursor: Boolean)

data class EventObservationRow(val eventName: String, val events: Long, val experiments: Long, val lastOccurredAt: LocalDateTime)
data class EventMetricRow(val eventName: String, val variant: String, val users: Long, val events: Long, val lastOccurredAt: LocalDateTime)
