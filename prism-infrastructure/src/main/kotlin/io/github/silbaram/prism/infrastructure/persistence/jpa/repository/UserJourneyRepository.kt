package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import jakarta.persistence.EntityManager
import org.springframework.stereotype.Repository
import java.time.LocalDateTime

data class JourneyUserRow(val userId: String, val variant: String, val firstAt: LocalDateTime,
    val lastAt: LocalDateTime, val exposures: Long, val events: Long, val goals: Long, val excluded: Long)
data class JourneyLogRow(val id: Long, val timestamp: LocalDateTime, val kind: Int,
    val eventName: String?, val impressionId: Long?, val attribution: Int, val clockSkew: Boolean)

/** HQL projections keep UTC timestamp converters and the database's exact identity ordering. */
@Repository
class UserJourneyRepository(private val entityManager: EntityManager) {
    fun users(key: String, from: LocalDateTime, until: LocalDateTime, goal: String?, user: String?,
              variant: String?, goalState: String, afterUser: String?, afterVariant: String?, limit: Int): List<JourneyUserRow> {
        fun filter(alias: String) = """
            $alias.experimentKey = :key AND $alias.timestamp >= :from AND $alias.timestamp < :until
            AND (:user IS NULL OR $alias.userId = :user) AND (:variant IS NULL OR $alias.variant = :variant)
            AND (:afterUser IS NULL OR $alias.userId > :afterUser
                OR ($alias.userId = :afterUser AND $alias.variant > :afterVariant))
        """
        val having = when (goalState) { "REACHED" -> "HAVING SUM(j.goals) > 0"; "NOT_REACHED" -> "HAVING SUM(j.goals) = 0"; else -> "" }
        val hql = """
            SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.JourneyUserRow(
                j.userId, j.variant, MIN(j.at), MAX(j.at), SUM(j.exposures), SUM(j.events), SUM(j.goals), SUM(j.excluded))
            FROM (
                SELECT i.userId AS userId, i.variant AS variant, i.timestamp AS at,
                    1L AS exposures, 0L AS events, 0L AS goals, 0L AS excluded
                FROM ImpressionLogEntity i WHERE ${filter("i")}
                UNION ALL
                SELECT c.userId AS userId, c.variant AS variant, c.timestamp AS at,
                    0L AS exposures, 1L AS events,
                    CASE WHEN c.eventName = :goal AND $ATTRIBUTED_CONVERSION THEN 1L ELSE 0L END AS goals,
                    CASE WHEN $ATTRIBUTED_CONVERSION THEN 0L ELSE 1L END AS excluded
                FROM ConversionLogEntity c WHERE ${filter("c")}
            ) j
            GROUP BY j.userId, j.variant $having ORDER BY j.userId, j.variant
        """
        return entityManager.createQuery(hql, JourneyUserRow::class.java)
            .setParameter("key", key).setParameter("from", from).setParameter("until", until)
            .setParameter("goal", goal).setParameter("user", user).setParameter("variant", variant)
            .setParameter("afterUser", afterUser).setParameter("afterVariant", afterVariant)
            .setMaxResults(limit).resultList
    }

    fun logs(key: String, user: String, variant: String, from: LocalDateTime, until: LocalDateTime,
             afterAt: LocalDateTime?, afterKind: Int?, afterId: Long?, limit: Int): List<JourneyLogRow> {
        fun filter(alias: String, kind: Int) = """
            $alias.experimentKey = :key AND $alias.userId = :user AND $alias.variant = :variant
            AND $alias.timestamp >= :from AND $alias.timestamp < :until
            AND (:afterAt IS NULL OR $alias.timestamp > :afterAt OR ($alias.timestamp = :afterAt
                AND ($kind > :afterKind OR ($kind = :afterKind AND $alias.id > :afterId))))
        """
        val exposure = """SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.JourneyLogRow(
            i.id, i.timestamp, 0, CAST(NULL AS String), CAST(NULL AS Long), 1, false)
            FROM ImpressionLogEntity i WHERE ${filter("i", 0)} ORDER BY i.timestamp, i.id"""
        val conversion = """SELECT new io.github.silbaram.prism.infrastructure.persistence.jpa.repository.JourneyLogRow(
            c.id, c.timestamp, 1, c.eventName, c.impressionId,
            CASE WHEN $ATTRIBUTED_CONVERSION THEN CASE WHEN c.impressionId IS NULL THEN 2 ELSE 1 END ELSE 0 END,
            CASE WHEN EXISTS (SELECT i.id FROM ImpressionLogEntity i WHERE i.id = c.impressionId
                AND i.experimentKey = c.experimentKey AND i.userId = c.userId AND i.variant = c.variant
                AND i.timestamp > c.timestamp) THEN true ELSE false END)
            FROM ConversionLogEntity c WHERE ${filter("c", 1)} ORDER BY c.timestamp, c.id"""
        fun read(hql: String) = entityManager.createQuery(hql, JourneyLogRow::class.java)
            .setParameter("key", key).setParameter("user", user).setParameter("variant", variant)
            .setParameter("from", from).setParameter("until", until).setParameter("afterAt", afterAt)
            .setParameter("afterKind", afterKind).setParameter("afterId", afterId).setMaxResults(limit).resultList
        return (read(exposure) + read(conversion))
            .sortedWith(compareBy<JourneyLogRow> { it.timestamp }.thenBy { it.kind }.thenBy { it.id }).take(limit)
    }
}
