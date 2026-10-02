package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ConversionLogRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.FunnelEventRow
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.JourneyPatternEventRow
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.HexFormat

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

internal fun validatePatternKey(key: String) {
    require(key.matches(Regex("[0-9a-f]{64}"))) { "조회할 경로 식별자를 확인하세요." }
}

/** Each inner list is an unordered, canonicalized multiset of simultaneous events. */
data class JourneyPatternPath(val groups: List<List<String>>, val truncated: Boolean) {
    /** Length-prefixed UTF-16 preserves exact strings, group boundaries, repeats, and the prefix marker. */
    val key: String get() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { output ->
            output.writeByte(1) // Version the representation independently of presentation labels.
            output.writeBoolean(truncated)
            output.writeInt(groups.size)
            groups.forEach { group ->
                output.writeInt(group.size)
                group.forEach { name ->
                    output.writeInt(name.length)
                    name.forEach { output.writeChar(it.code) }
                }
            }
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()))
    }
}
data class JourneyPatternRow(val path: JourneyPatternPath, val users: Long, val goals: Long,
    val sharePercent: Double, val goalPercent: Double?, val sampleUsers: List<String>)
data class JourneyPatternVariant(val variant: String, val users: Long, val excludedUsers: Long,
    val patternCount: Int, val unlistedUsers: Long, val patterns: List<JourneyPatternRow>)
data class JourneyPatternReport(val groups: List<JourneyPatternVariant>, val events: Int, val goalEventName: String?)
data class JourneyPatternUser(val userId: String, val variant: String, val firstAt: LocalDateTime,
    val lastAt: LocalDateTime, val events: Long, val hasGoal: Boolean, val path: JourneyPatternPath?,
    internal val afterCursor: Boolean = true)
data class JourneyPatternUsers(val items: List<JourneyPatternUser>, val nextUser: String?, val totalUsers: Long,
    val goalUsers: Long?, val matchedUsers: Long, val path: JourneyPatternPath?, val goalEventName: String?)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class JourneyPatternService(private val experiments: ExperimentRepository, private val conversions: ConversionLogRepository) {
    fun report(id: Long, query: JourneyPatternQuery): JourneyPatternReport {
        query.validate()
        val experiment = experiments.findById(id).orElseThrow { ExperimentNotFoundException(id) }
        val variants = experiment.variants.map { it.name }.filter { query.variant == null || it == query.variant }
        val accumulator = JourneyPatternAccumulator(query, experiment.goalEventName?.takeIf(String::isNotBlank), variants)
        scan(experiment.key, query) { accumulator.accept(it) }
        return accumulator.finish()
    }

    fun users(id: Long, query: JourneyPatternQuery, pathKey: String, goalState: String = "ALL",
        afterUser: String? = null, size: Int = 50): JourneyPatternUsers {
        query.validate()
        require(query.variant != null) { "사용자 목록을 조회할 변형을 지정하세요." }
        validatePatternKey(pathKey)
        validateJourneyIdentity(afterUser)
        require(size in 1..100) { "페이지 크기는 1–100이어야 합니다." }
        require(goalState in setOf("ALL", "REACHED", "NOT_REACHED")) { "목표 발생 조건을 확인하세요." }
        val experiment = experiments.findById(id).orElseThrow { ExperimentNotFoundException(id) }
        val goal = experiment.goalEventName?.takeIf(String::isNotBlank)
        require(goal != null || goalState == "ALL") { "목표 이벤트가 없는 실험에서는 목표 발생 조건으로 필터링할 수 없습니다." }
        val selected = JourneyPatternUserAccumulator(pathKey, goal, goalState, size)
        val classifier = JourneyPatternClassifier(query.depth, goal, consume = selected::accept)
        scan(experiment.key, query, afterUser) { classifier.accept(it) }
        classifier.finish()
        return selected.finish()
    }

    /** Scan all histories, including those before the page cursor, for consistent full-path counts and goals. */
    private fun scan(key: String, query: JourneyPatternQuery, afterUser: String? = null,
        consume: (JourneyPatternEventRow) -> Unit) {
        var cursor: JourneyPatternEventRow? = null
        val page = PageRequest.of(0, 1000)
        while (true) {
            val events = conversions.findJourneyPatternEvents(key, query.from, query.until, query.variant,
                cursor?.userId.orEmpty(), cursor?.variant.orEmpty(), cursor?.timestamp ?: query.from, cursor?.id ?: 0L,
                page, afterUser)
            events.forEach(consume)
            if (events.size < page.pageSize) break
            cursor = events.last()
        }
    }
}

/** The report and full user lists share one bounded classifier, including goals beyond a displayed prefix. */
internal class JourneyPatternClassifier(private val depth: Int, private val goal: String?, private val maxEvents: Int = 200_000,
    private val consume: (JourneyPatternUser) -> Unit) {
    var events = 0
        private set
    private var user: String? = null
    private var variant: String? = null
    private var hasGoal = false
    private var firstAt: LocalDateTime? = null
    private var lastAt: LocalDateTime? = null
    private var userEvents = 0L
    private var afterCursor = true
    private var at: LocalDateTime? = null
    private val prefix = mutableListOf<List<String>>()
    private val simultaneous = mutableListOf<String>()
    private var length = 0
    private var truncated = false

    fun accept(event: JourneyPatternEventRow) = accept(FunnelEventRow(event.id, event.userId, event.variant,
        event.eventName, event.timestamp), event.afterCursor)

    fun accept(event: FunnelEventRow, isAfterCursor: Boolean = true) {
        require(++events <= maxEvents) { "조회 이벤트가 ${maxEvents}건을 초과했습니다. 기간을 줄이거나 변형을 지정하세요. 부분 집계는 제공하지 않습니다." }
        if (event.userId != user || event.variant != variant) {
            finishUser()
            user = event.userId; variant = event.variant; hasGoal = false; at = null
            firstAt = event.timestamp; lastAt = event.timestamp; userEvents = 0; afterCursor = isAfterCursor
            prefix.clear(); simultaneous.clear(); length = 0; truncated = false
        }
        lastAt = event.timestamp
        userEvents++
        if (event.eventName == goal) hasGoal = true // Include goals beyond the displayed prefix.
        if (truncated) return
        if (at?.isEqual(event.timestamp) != true) {
            finishTimestamp()
            at = event.timestamp
        }
        if (length + simultaneous.size == depth) {
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
        consume(JourneyPatternUser(id, variant!!, firstAt!!, lastAt!!, userEvents, hasGoal,
            prefix.takeIf { it.isNotEmpty() }?.let { JourneyPatternPath(it.toList(), truncated) }, afterCursor))
    }

    fun finish() {
        finishUser()
        user = null
    }
}

/** Retains at most depth names per current user and three example IDs per pattern. */
internal class JourneyPatternAccumulator(private val query: JourneyPatternQuery, private val goal: String?, variants: List<String>,
    maxEvents: Int = 200_000, private val maxPatterns: Int = 5_000) {
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
    private var patternCount = 0
    private val classifier = JourneyPatternClassifier(query.depth, goal, maxEvents, ::finishUser)

    fun accept(event: FunnelEventRow) = classifier.accept(event)
    fun accept(event: JourneyPatternEventRow) = classifier.accept(event)

    private fun finishUser(user: JourneyPatternUser) {
        val group = groups.getOrPut(user.variant) { Group() }
        group.users++
        val path = user.path ?: run { group.excluded++; return }
        val count = group.paths[path] ?: Count().also {
            require(++patternCount <= maxPatterns) { "서로 다른 경로가 ${maxPatterns}개를 초과했습니다. 기간을 줄이거나 변형을 지정하세요. 부분 집계는 제공하지 않습니다." }
            group.paths[path] = it
        }
        count.users++
        if (user.hasGoal) count.goals++
        if (count.examples.size < 3) count.examples += user.userId
    }

    fun finish(): JourneyPatternReport {
        classifier.finish()
        return JourneyPatternReport(groups.map { (variant, group) ->
            // Stable ties retain the database's first-user order; names are never joined to create a key.
            val rows = group.paths.entries.sortedByDescending { it.value.users }.take(query.top).map { (path, count) ->
                JourneyPatternRow(path, count.users, count.goals, count.users.toDouble() / group.users * 100,
                    goal?.let { count.goals.toDouble() / count.users * 100 }, count.examples.toList())
            }
            JourneyPatternVariant(variant, group.users, group.excluded, group.paths.size,
                group.users - group.excluded - rows.sumOf { it.users }, rows)
        }, classifier.events, goal)
    }
}

/** Keep complete totals for one path but retain only a single page plus the next-user sentinel. */
internal class JourneyPatternUserAccumulator(private val pathKey: String, private val goal: String?,
    private val goalState: String, private val size: Int) {
    private val items = mutableListOf<JourneyPatternUser>()
    private var total = 0L
    private var goals = 0L
    private var matched = 0L
    private var path: JourneyPatternPath? = null

    fun accept(user: JourneyPatternUser) {
        val candidate = user.path?.takeIf { it.key == pathKey } ?: return
        path = candidate
        total++
        if (user.hasGoal) goals++
        if ((goalState == "REACHED" && !user.hasGoal) || (goalState == "NOT_REACHED" && user.hasGoal)) return
        matched++
        if (user.afterCursor && items.size <= size) items += user
    }

    fun finish(): JourneyPatternUsers {
        val page = items.take(size)
        return JourneyPatternUsers(page, page.lastOrNull()?.userId?.takeIf { items.size > size },
            total, goals.takeIf { goal != null }, matched, path, goal)
    }
}
