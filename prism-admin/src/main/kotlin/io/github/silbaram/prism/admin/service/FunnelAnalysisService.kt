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
import java.time.ZoneOffset

data class FunnelQuery(val steps: List<String>, val from: LocalDateTime, val until: LocalDateTime, val windowHours: Int) {
    fun validate(now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)) {
        require(steps.size in 2..8 && steps.all { it.isNotBlank() && it.length <= 255 }) { "이벤트를 한 줄씩 2–8개 입력하세요. 각 이름은 1–255자입니다." }
        require(steps.distinct().size == steps.size) { "퍼널 단계의 이벤트 이름은 중복할 수 없습니다." }
        require(windowHours in 1..720) { "전환 제한 시간은 1–720시간입니다." }
        validateJourneyPeriod(from, until, now)
    }
}

data class FunnelStage(val eventName: String, val users: Long, val fromPreviousPercent: Double?, val fromFirstPercent: Double?)
data class FunnelGroup(val variant: String, val stages: List<FunnelStage>, val pendingUsers: Long)
data class FunnelReport(val query: FunnelQuery, val groups: List<FunnelGroup>)

enum class FunnelSelection { REACHED, MISSING, PENDING }
data class FunnelUser(val userId: String, val variant: String, val firstAt: LocalDateTime,
    val lastReachedAt: LocalDateTime, val reached: Int, val pending: Boolean)
data class FunnelUsers(val items: List<FunnelUser>, val nextUser: String?)

/** Both aggregates and user drill-down consume this same bounded, ordered scan. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class FunnelAnalysisService(private val experiments: ExperimentRepository, private val conversions: ConversionLogRepository) {
    fun report(experimentId: Long, query: FunnelQuery): FunnelReport {
        query.validate()
        val experiment = experiments.findById(experimentId).orElseThrow { ExperimentNotFoundException(experimentId) }
        val counts = linkedMapOf<String, LongArray>()
        val pending = mutableMapOf<String, Long>()
        experiment.variants.forEach { counts[it.name] = LongArray(query.steps.size) }
        scan(experiment.key, query) { user ->
            if (user.pending) pending[user.variant] = (pending[user.variant] ?: 0) + 1
            else {
                val group = counts.getOrPut(user.variant) { LongArray(query.steps.size) }
                repeat(user.reached) { group[it]++ }
            }
            true
        }
        return FunnelReport(query, (counts.keys + pending.keys).distinct().map { variant ->
            val group = counts[variant] ?: LongArray(query.steps.size)
            FunnelGroup(variant, query.steps.mapIndexed { index, name ->
                fun percent(denominator: Long) = if (denominator == 0L) null else group[index].toDouble() / denominator * 100
                FunnelStage(name, group[index], if (index == 0) null else percent(group[index - 1]), percent(group[0]))
            }, pending[variant] ?: 0L)
        })
    }

    fun users(experimentId: Long, query: FunnelQuery, variant: String, stage: Int,
              selection: FunnelSelection, afterUser: String? = null, size: Int = 50): FunnelUsers {
        query.validate(); validateJourneyIdentity(variant); validateJourneyIdentity(afterUser)
        require(stage in 1..query.steps.size && (selection != FunnelSelection.MISSING || stage >= 2)) { "조회할 퍼널 단계를 확인하세요." }
        require(size in 1..100) { "페이지 크기는 1–100이어야 합니다." }
        val key = experiments.findById(experimentId).orElseThrow { ExperimentNotFoundException(experimentId) }.key
        val users = mutableListOf<FunnelUser>()
        scan(key, query, variant = variant, afterUser = afterUser) { user ->
            val matches = when (selection) {
                FunnelSelection.REACHED -> !user.pending && user.reached >= stage
                FunnelSelection.MISSING -> !user.pending && user.reached == stage - 1
                FunnelSelection.PENDING -> user.pending
            }
            if (matches) users += user
            users.size <= size
        }
        val items = users.take(size)
        return FunnelUsers(items, items.lastOrNull()?.userId?.takeIf { users.size > size })
    }

    fun user(experimentId: Long, query: FunnelQuery, userId: String, variant: String): FunnelUser? {
        query.validate(); validateJourneyIdentity(userId); validateJourneyIdentity(variant)
        val key = experiments.findById(experimentId).orElseThrow { ExperimentNotFoundException(experimentId) }.key
        var result: FunnelUser? = null
        scan(key, query, userId, variant) { result = it; false }
        return result
    }

    private fun scan(key: String, query: FunnelQuery, userId: String? = null, variant: String? = null,
                     afterUser: String? = null, consume: (FunnelUser) -> Boolean) {
        var currentUser: String? = null
        var currentVariant: String? = null
        var firstAt: LocalDateTime? = null
        var previousAt: LocalDateTime? = null
        var reached = 0
        fun finishUser(): Boolean {
            val first = firstAt ?: return true
            return consume(FunnelUser(requireNotNull(currentUser), requireNotNull(currentVariant), first,
                requireNotNull(previousAt), reached, first.plusHours(query.windowHours.toLong()) > query.until))
        }
        var cursor: FunnelEventRow? = null
        val page = PageRequest.of(0, 1000)
        while (true) {
            val events = conversions.findFunnelEvents(key, query.steps, query.from, query.until,
                cursor?.userId.orEmpty(), cursor?.variant.orEmpty(), cursor?.timestamp ?: query.from, cursor?.id ?: 0L,
                page, userId, variant, afterUser)
            if (events.isEmpty()) break
            for (event in events) {
                if (currentUser != event.userId || currentVariant != event.variant) {
                    if (!finishUser()) return
                    currentUser = event.userId; currentVariant = event.variant
                    firstAt = null; previousAt = null; reached = 0
                }
                if (reached >= query.steps.size || event.eventName != query.steps[reached]) continue
                val first = firstAt
                if (first == null) {
                    firstAt = event.timestamp; previousAt = event.timestamp; reached = 1
                } else if (event.timestamp > requireNotNull(previousAt) && event.timestamp < first.plusHours(query.windowHours.toLong())) {
                    previousAt = event.timestamp; reached++
                }
            }
            cursor = events.last()
            if (events.size < page.pageSize) break
        }
        finishUser()
    }
}
