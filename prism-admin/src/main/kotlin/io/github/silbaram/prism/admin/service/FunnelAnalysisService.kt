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
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

data class FunnelQuery(val steps: List<String>, val from: LocalDateTime, val until: LocalDateTime, val windowHours: Int) {
    fun validate(now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)) {
        require(steps.size in 2..8 && steps.all { it.isNotBlank() && it.length <= 255 }) { "이벤트를 한 줄씩 2–8개 입력하세요. 각 이름은 1–255자입니다." }
        require(steps.distinct().size == steps.size) { "퍼널 단계의 이벤트 이름은 중복할 수 없습니다." }
        require(windowHours in 1..720) { "전환 제한 시간은 1–720시간입니다." }
        require(from >= LocalDateTime.of(1970, 1, 1, 0, 0) && from.nano % 1000 == 0 && until.nano % 1000 == 0) {
            "조회 시각은 1970년 이후이며 마이크로초 이하의 정밀도만 지원합니다."
        }
        require(from < until && Duration.between(from, until) <= Duration.ofDays(366)) { "조회 시작은 종료보다 빨라야 하며 최대 366일입니다." }
        require(until <= now) { "조회 종료 시각은 현재 UTC 시각 이후일 수 없습니다." }
    }
}

data class FunnelStage(val eventName: String, val users: Long, val fromPreviousPercent: Double?, val fromFirstPercent: Double?)
data class FunnelGroup(val variant: String, val stages: List<FunnelStage>, val pendingUsers: Long)
data class FunnelReport(val query: FunnelQuery, val groups: List<FunnelGroup>)

/** Descriptive ordered funnels anchored to the first selected event for each user/variant in the period. */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class FunnelAnalysisService(private val experiments: ExperimentRepository, private val conversions: ConversionLogRepository) {
    fun report(experimentId: Long, query: FunnelQuery): FunnelReport {
        query.validate()
        val experiment = experiments.findById(experimentId).orElseThrow { ExperimentNotFoundException(experimentId) }
        val counts = linkedMapOf<String, LongArray>()
        val pending = mutableMapOf<String, Long>()
        experiment.variants.forEach { counts[it.name] = LongArray(query.steps.size) }
        var currentUser: String? = null
        var currentVariant: String? = null
        var firstAt: LocalDateTime? = null
        var previousAt: LocalDateTime? = null
        var reached = 0

        fun finishUser() {
            val first = firstAt ?: return
            val variant = requireNotNull(currentVariant)
            if (first.plusHours(query.windowHours.toLong()) > query.until) {
                pending[variant] = (pending[variant] ?: 0) + 1
            } else {
                val group = counts.getOrPut(variant) { LongArray(query.steps.size) }
                repeat(reached) { group[it]++ }
            }
        }

        var cursor: FunnelEventRow? = null
        val page = PageRequest.of(0, 1000)
        while (true) {
            val events = conversions.findFunnelEvents(experiment.key, query.steps, query.from, query.until,
                cursor?.userId.orEmpty(), cursor?.variant.orEmpty(), cursor?.timestamp ?: query.from, cursor?.id ?: 0L, page)
            if (events.isEmpty()) break
            for (event in events) {
                if (currentUser != event.userId || currentVariant != event.variant) {
                    finishUser()
                    currentUser = event.userId
                    currentVariant = event.variant
                    firstAt = null
                    previousAt = null
                    reached = 0
                }
                if (reached >= query.steps.size || event.eventName != query.steps[reached]) continue
                val first = firstAt
                if (first == null) {
                    firstAt = event.timestamp
                    previousAt = event.timestamp
                    reached = 1
                } else if (event.timestamp > requireNotNull(previousAt) && event.timestamp < first.plusHours(query.windowHours.toLong())) {
                    previousAt = event.timestamp
                    reached++
                }
            }
            cursor = events.last()
            if (events.size < page.pageSize) break
        }
        finishUser()
        val variants = (counts.keys + pending.keys).distinct()
        return FunnelReport(query, variants.map { variant ->
            val group = counts[variant] ?: LongArray(query.steps.size)
            FunnelGroup(variant, query.steps.mapIndexed { index, name ->
                fun percent(denominator: Long) = if (denominator == 0L) null else group[index].toDouble() / denominator * 100
                FunnelStage(name, group[index], if (index == 0) null else percent(group[index - 1]), percent(group[0]))
            }, pending[variant] ?: 0L)
        })
    }
}
