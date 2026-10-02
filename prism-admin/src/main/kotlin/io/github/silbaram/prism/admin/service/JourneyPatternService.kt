package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ConversionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.FunnelEventRow
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

data class JourneyPatternQuery(val from: LocalDateTime, val until: LocalDateTime, val variant: String? = null,
    val depth: Int = 5, val top: Int = 20) {
    fun validate() {
        validateJourneyPeriod(from, until)
        validateJourneyIdentity(variant)
        validatePatternOptions(depth, top)
    }
}

internal fun validatePatternOptions(depth: Int, top: Int) {
    require(depth in 2..8 && top in 1..50) { "경로 길이는 2–8개, 표시할 경로 수는 1–50개여야 합니다." }
}

/** Each inner list is an unordered, canonicalized multiset of simultaneous events. */
data class JourneyPatternPath(val groups: List<List<String>>, val truncated: Boolean)
data class JourneyPatternRow(val path: JourneyPatternPath, val users: Long, val goals: Long,
    val sharePercent: Double, val goalPercent: Double?, val sampleUsers: List<String>)
data class JourneyPatternVariant(val variant: String, val users: Long, val excludedUsers: Long,
    val patternCount: Int, val unlistedUsers: Long, val patterns: List<JourneyPatternRow>)
data class JourneyPatternReport(val groups: List<JourneyPatternVariant>, val events: Int, val goalEventName: String?)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class JourneyPatternService(private val experiments: ExperimentRepository, private val conversions: ConversionLogRepository) {
    fun report(id: Long, query: JourneyPatternQuery): JourneyPatternReport {
        query.validate()
        val experiment = experiments.findById(id).orElseThrow { ExperimentNotFoundException(id) }
        val variants = experiment.variants.map { it.name }.filter { query.variant == null || it == query.variant }
        val accumulator = JourneyPatternAccumulator(query, experiment.goalEventName?.takeIf(String::isNotBlank), variants)
        var cursor: FunnelEventRow? = null
        val page = PageRequest.of(0, 1000)
        while (true) {
            val events = conversions.findJourneyPatternEvents(experiment.key, query.from, query.until, query.variant,
                cursor?.userId.orEmpty(), cursor?.variant.orEmpty(), cursor?.timestamp ?: query.from, cursor?.id ?: 0L, page)
            events.forEach(accumulator::accept)
            if (events.size < page.pageSize) break
            cursor = events.last()
        }
        return accumulator.finish()
    }
}

/** Retains at most depth names per current user and three example IDs per pattern. */
internal class JourneyPatternAccumulator(private val query: JourneyPatternQuery, private val goal: String?, variants: List<String>,
    private val maxEvents: Int = 200_000, private val maxPatterns: Int = 5_000) {
    private class Count {
        var users = 0L
        var goals = 0L
        val examples = mutableListOf<String>()
    }
    private class Group {
        var users = 0L
        var excluded = 0L
        val paths = linkedMapOf<JourneyPatternPath, Count>()
    }
    private val groups = linkedMapOf<String, Group>().apply { variants.forEach { put(it, Group()) } }
    private var events = 0
    private var patternCount = 0
    private var user: String? = null
    private var variant: String? = null
    private var hasGoal = false
    private var at: LocalDateTime? = null
    private val prefix = mutableListOf<List<String>>()
    private val simultaneous = mutableListOf<String>()
    private var length = 0
    private var truncated = false

    fun accept(event: FunnelEventRow) {
        require(++events <= maxEvents) { "조회 이벤트가 ${maxEvents}건을 초과했습니다. 기간을 줄이거나 변형을 지정하세요. 부분 집계는 제공하지 않습니다." }
        if (event.userId != user || event.variant != variant) {
            finishUser()
            user = event.userId; variant = event.variant; hasGoal = false; at = null
            prefix.clear(); simultaneous.clear(); length = 0; truncated = false
        }
        if (event.eventName == goal) hasGoal = true // Include goals beyond the displayed prefix.
        if (truncated) return
        if (at?.isEqual(event.timestamp) != true) {
            finishTimestamp()
            at = event.timestamp
        }
        if (length + simultaneous.size == query.depth) {
            // Never invent an order by keeping only part of a same-timestamp group.
            simultaneous.clear()
            truncated = true
        } else simultaneous += event.eventName
    }

    private fun finishTimestamp() {
        if (simultaneous.isEmpty()) return
        prefix += simultaneous.sorted()
        length += simultaneous.size
        simultaneous.clear()
    }

    private fun finishUser() {
        val id = user ?: return
        finishTimestamp()
        val group = groups.getOrPut(variant!!) { Group() }
        group.users++
        if (prefix.isEmpty()) { group.excluded++; return }
        val path = JourneyPatternPath(prefix.toList(), truncated)
        val count = group.paths[path] ?: Count().also {
            require(++patternCount <= maxPatterns) { "서로 다른 경로가 ${maxPatterns}개를 초과했습니다. 기간을 줄이거나 변형을 지정하세요. 부분 집계는 제공하지 않습니다." }
            group.paths[path] = it
        }
        count.users++
        if (hasGoal) count.goals++
        if (count.examples.size < 3) count.examples += id
    }

    fun finish(): JourneyPatternReport {
        finishUser()
        user = null
        return JourneyPatternReport(groups.map { (variant, group) ->
            // Stable ties retain the database's first-user order; names are never joined to create a key.
            val rows = group.paths.entries.sortedByDescending { it.value.users }.take(query.top).map { (path, count) ->
                JourneyPatternRow(path, count.users, count.goals, count.users.toDouble() / group.users * 100,
                    goal?.let { count.goals.toDouble() / count.users * 100 }, count.examples.toList())
            }
            JourneyPatternVariant(variant, group.users, group.excluded, group.paths.size,
                group.users - group.excluded - rows.sumOf { it.users }, rows)
        }, events, goal)
    }
}
