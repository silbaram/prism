package io.github.silbaram.prism.admin.service

import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.*
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset

internal fun validateJourneyPeriod(from: LocalDateTime, until: LocalDateTime, now: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)) {
    require(from >= LocalDateTime.of(1970, 1, 1, 0, 0) && from.nano % 1000 == 0 && until.nano % 1000 == 0) {
        "조회 시각은 1970년 이후이며 마이크로초 이하의 정밀도만 지원합니다."
    }
    require(from < until && Duration.between(from, until) <= Duration.ofDays(366)) { "조회 시작은 종료보다 빨라야 하며 최대 366일입니다." }
    require(until <= now) { "조회 종료 시각은 현재 UTC 시각 이후일 수 없습니다." }
}

internal fun validateJourneyIdentity(value: String?) {
    require(value == null || (value.isNotBlank() && value.length <= 255)) { "사용자 ID와 변형은 공백뿐인 값이 아닌 1–255자여야 합니다." }
}

data class JourneyQuery(val from: LocalDateTime, val until: LocalDateTime, val userId: String? = null,
    val variant: String? = null, val goalState: String = "ALL", val size: Int = 50) {
    fun validate() {
        validateJourneyPeriod(from, until)
        validateJourneyIdentity(userId); validateJourneyIdentity(variant)
        require(goalState in setOf("ALL", "REACHED", "NOT_REACHED")) { "목표 이벤트 조회 조건을 확인하세요." }
        require(size in 1..100) { "페이지 크기는 1–100이어야 합니다." }
    }
}
data class JourneyUsers(val items: List<JourneyUserRow>, val next: JourneyUserRow?)
data class JourneyCursor(val at: LocalDateTime, val kind: Int, val id: Long)
data class JourneyEntry(val log: JourneyLogRow, val elapsed: String?, val simultaneous: Boolean)
data class JourneyTimeline(val summary: JourneyUserRow?, val entries: List<JourneyEntry>, val next: JourneyCursor?)
data class JourneyDetails(val timeline: JourneyTimeline, val funnelUser: FunnelUser?)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class UserJourneyService(private val experiments: ExperimentRepository, private val journeys: UserJourneyRepository,
                         private val impressions: ImpressionLogRepository, private val conversions: ConversionLogRepository,
                         private val funnels: FunnelAnalysisService) {
    /** Keep all sections of a detail page on one snapshot when events arrive during the request. */
    fun details(id: Long, query: JourneyQuery, cursor: JourneyCursor? = null, funnel: FunnelQuery? = null): JourneyDetails {
        query.validate()
        if (funnel != null) {
            funnel.validate()
            require(funnel.from.isEqual(query.from) && funnel.until.isEqual(query.until)) { "타임라인과 퍼널의 조회 기간이 같아야 합니다." }
        }
        val timeline = timeline(id, query, cursor)
        return JourneyDetails(timeline, funnel?.let { funnels.user(id, it, query.userId!!, query.variant!!) })
    }

    fun users(id: Long, query: JourneyQuery, afterUser: String? = null, afterVariant: String? = null): JourneyUsers {
        query.validate()
        require((afterUser == null) == (afterVariant == null)) { "사용자 목록의 다음 페이지 조건을 확인하세요." }
        validateJourneyIdentity(afterUser); validateJourneyIdentity(afterVariant)
        val experiment = experiments.findById(id).orElseThrow { ExperimentNotFoundException(id) }
        val goal = experiment.goalEventName?.takeIf(String::isNotBlank)
        require(goal != null || query.goalState == "ALL") { "목표 이벤트가 없는 실험은 달성 여부로 검색할 수 없습니다." }
        val rows = journeys.users(experiment.key, query.from, query.until, goal, query.userId, query.variant,
            query.goalState, afterUser, afterVariant, query.size + 1)
        val items = rows.take(query.size)
        return JourneyUsers(items, items.lastOrNull()?.takeIf { rows.size > query.size })
    }

    fun timeline(id: Long, query: JourneyQuery, cursor: JourneyCursor? = null): JourneyTimeline {
        query.validate()
        val user = query.userId; val variant = query.variant
        require(user != null && variant != null) { "사용자 ID와 변형을 지정하세요." }
        val experiment = experiments.findById(id).orElseThrow { ExperimentNotFoundException(id) }
        if (cursor != null) {
            require(cursor.id > 0 && cursor.kind in 0..1 && cursor.at >= query.from && cursor.at < query.until) { "타임라인의 다음 페이지 조건을 확인하세요." }
            val matches = if (cursor.kind == 0) impressions.findById(cursor.id).orElse(null)?.let {
                it.experimentKey == experiment.key && it.userId == user && it.variant == variant && it.timestamp.isEqual(cursor.at)
            } else conversions.findById(cursor.id).orElse(null)?.let {
                it.experimentKey == experiment.key && it.userId == user && it.variant == variant && it.timestamp.isEqual(cursor.at)
            }
            require(matches == true) { "타임라인 기준 이벤트를 찾을 수 없습니다. 처음부터 다시 조회하세요." }
        }
        val rows = journeys.logs(experiment.key, user!!, variant!!, query.from, query.until,
            cursor?.at, cursor?.kind, cursor?.id, query.size + 1)
        var previous = cursor?.at
        val entries = rows.take(query.size).map { row ->
            val duration = previous?.let { Duration.between(it, row.timestamp) }
            previous = row.timestamp
            JourneyEntry(row, duration?.let { "${it.toSeconds()}.${(it.nano / 1000).toString().padStart(6, '0')}초" }, duration?.isZero == true)
        }
        val summary = journeys.users(experiment.key, query.from, query.until, experiment.goalEventName?.takeIf(String::isNotBlank),
            user, variant, "ALL", null, null, 1).firstOrNull()
        val next = entries.lastOrNull()?.log?.takeIf { rows.size > query.size }?.let { JourneyCursor(it.timestamp, it.kind, it.id) }
        return JourneyTimeline(summary, entries, next)
    }
}
