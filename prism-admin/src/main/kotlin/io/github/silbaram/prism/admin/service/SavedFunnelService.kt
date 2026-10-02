package io.github.silbaram.prism.admin.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.silbaram.prism.admin.exception.ExperimentNotFoundException
import io.github.silbaram.prism.admin.exception.SavedFunnelConflictException
import io.github.silbaram.prism.admin.exception.SavedFunnelNotFoundException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.SavedFunnelEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.ExperimentRepository
import io.github.silbaram.prism.infrastructure.persistence.jpa.repository.SavedFunnelRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

enum class SavedFunnelPeriod(val label: String) {
    LAST_7_DAYS("최근 7일"), LAST_30_DAYS("최근 30일"), FIXED("고정 기간")
}

internal fun savedFunnelNow(): LocalDateTime = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS)

data class SavedFunnelInput(val name: String, val description: String, val steps: List<String>, val windowHours: Int,
    val periodMode: SavedFunnelPeriod, val fromAt: LocalDateTime? = null, val untilAt: LocalDateTime? = null) {
    fun query(now: LocalDateTime): FunnelQuery {
        val until = if (periodMode == SavedFunnelPeriod.FIXED) untilAt else now
        val from = when (periodMode) {
            SavedFunnelPeriod.LAST_7_DAYS -> now.minusDays(7)
            SavedFunnelPeriod.LAST_30_DAYS -> now.minusDays(30)
            SavedFunnelPeriod.FIXED -> fromAt
        }
        require(from != null && until != null) { "고정 기간의 시작과 종료를 입력하세요." }
        return FunnelQuery(steps, from!!, until!!, windowHours).also { it.validate(now) }
    }

    fun validate(now: LocalDateTime = savedFunnelNow()) {
        require(name.trim().isNotEmpty() && name.trim().length <= 120 && '\n' !in name && '\r' !in name) {
            "퍼널 이름은 줄바꿈 없는 1–120자여야 합니다."
        }
        require(description.length <= 1000) { "설명은 1,000자 이하여야 합니다." }
        require(steps.none { '\n' in it || '\r' in it }) { "이벤트 이름에는 줄바꿈을 넣을 수 없습니다." }
        query(now)
    }
}

data class SavedFunnelView(val id: Long, val name: String, val description: String, val steps: List<String>,
    val windowHours: Int, val periodMode: SavedFunnelPeriod, val fromAt: LocalDateTime?, val untilAt: LocalDateTime?,
    val version: Long, val createdAt: LocalDateTime, val updatedAt: LocalDateTime) {
    val periodLabel: String get() = periodMode.label
    fun input() = SavedFunnelInput(name, description, steps, windowHours, periodMode, fromAt, untilAt)
}
data class SavedFunnelPage(val items: List<SavedFunnelView>, val nextId: Long?)
data class SavedFunnelRun(val saved: SavedFunnelView, val query: FunnelQuery, val report: FunnelReport)

@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
class SavedFunnelService(private val experiments: ExperimentRepository, private val saved: SavedFunnelRepository,
    private val funnels: FunnelAnalysisService) {
    private val json = jacksonObjectMapper()

    fun list(experimentId: Long, afterId: Long? = null): SavedFunnelPage {
        require(afterId == null || afterId > 0) { "목록의 다음 페이지 조건을 확인하세요." }
        ensureExperiment(experimentId)
        val rows = saved.findByExperimentIdAndIdGreaterThanOrderByIdAsc(experimentId, afterId ?: 0L, PageRequest.of(0, 51))
        val items = rows.take(50).map(::view)
        return SavedFunnelPage(items, items.lastOrNull()?.id?.takeIf { rows.size > 50 })
    }

    fun get(experimentId: Long, funnelId: Long): SavedFunnelView {
        ensureExperiment(experimentId)
        return view(find(experimentId, funnelId))
    }

    /** Resolve rolling periods once, then reuse the concrete bounds for every drill-down link. */
    fun run(experimentId: Long, funnelId: Long, now: LocalDateTime = savedFunnelNow()): SavedFunnelRun {
        val definition = get(experimentId, funnelId)
        val query = definition.input().query(now)
        return SavedFunnelRun(definition, query, funnels.report(experimentId, query))
    }

    @Transactional
    fun create(experimentId: Long, input: SavedFunnelInput): SavedFunnelView {
        val now = savedFunnelNow()
        input.validate(now)
        // Serialize definition writes with draft deletion; the database also enforces uniqueness and the FK.
        val experiment = experiments.findForUpdate(experimentId) ?: throw ExperimentNotFoundException(experimentId)
        require(!saved.existsByExperimentIdAndName(experimentId, input.name.trim())) { "이 실험에 같은 이름의 퍼널이 있습니다." }
        val entity = SavedFunnelEntity(experiment = experiment, name = input.name.trim(), description = input.description,
            stepsJson = json.writeValueAsString(input.steps), windowHours = input.windowHours, periodMode = input.periodMode.name,
            fromAt = input.fromAt.takeIf { input.periodMode == SavedFunnelPeriod.FIXED },
            untilAt = input.untilAt.takeIf { input.periodMode == SavedFunnelPeriod.FIXED }, createdAt = now, updatedAt = now)
        return view(saved.saveAndFlush(entity))
    }

    @Transactional
    fun update(experimentId: Long, funnelId: Long, version: Long, input: SavedFunnelInput): SavedFunnelView {
        val now = savedFunnelNow()
        input.validate(now)
        experiments.findForUpdate(experimentId) ?: throw ExperimentNotFoundException(experimentId)
        val entity = find(experimentId, funnelId)
        checkVersion(entity, version)
        require(!saved.existsByExperimentIdAndNameAndIdNot(experimentId, input.name.trim(), funnelId)) { "이 실험에 같은 이름의 퍼널이 있습니다." }
        entity.name = input.name.trim(); entity.description = input.description
        entity.stepsJson = json.writeValueAsString(input.steps); entity.windowHours = input.windowHours
        entity.periodMode = input.periodMode.name
        entity.fromAt = input.fromAt.takeIf { input.periodMode == SavedFunnelPeriod.FIXED }
        entity.untilAt = input.untilAt.takeIf { input.periodMode == SavedFunnelPeriod.FIXED }
        entity.updatedAt = now
        return view(saved.saveAndFlush(entity))
    }

    @Transactional
    fun delete(experimentId: Long, funnelId: Long, version: Long) {
        experiments.findForUpdate(experimentId) ?: throw ExperimentNotFoundException(experimentId)
        val entity = find(experimentId, funnelId)
        checkVersion(entity, version)
        saved.delete(entity)
        saved.flush()
    }

    private fun checkVersion(entity: SavedFunnelEntity, version: Long) {
        require(version >= 0) { "변경 기준 버전을 확인하세요." }
        if (entity.version != version) throw SavedFunnelConflictException()
    }

    private fun ensureExperiment(id: Long) {
        if (!experiments.existsById(id)) throw ExperimentNotFoundException(id)
    }

    private fun find(experimentId: Long, id: Long) = saved.findByIdAndExperimentId(id, experimentId)
        ?: throw SavedFunnelNotFoundException()

    private fun view(entity: SavedFunnelEntity) = SavedFunnelView(entity.id!!, entity.name, entity.description,
        json.readValue<List<String>>(entity.stepsJson), entity.windowHours, SavedFunnelPeriod.valueOf(entity.periodMode),
        entity.fromAt, entity.untilAt, entity.version!!, entity.createdAt, entity.updatedAt)
}
