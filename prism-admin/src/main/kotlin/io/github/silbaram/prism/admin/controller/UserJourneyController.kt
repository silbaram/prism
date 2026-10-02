package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.admin.service.*
import io.github.silbaram.prism.admin.controller.dto.JourneyNavigation
import io.github.silbaram.prism.admin.controller.dto.JourneyListSource
import jakarta.servlet.http.HttpServletRequest
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

internal fun journeyTime(value: String): LocalDateTime = try { LocalDateTime.parse(value) }
    catch (_: java.time.format.DateTimeParseException) { throw AdminValidationException("조회 시각을 UTC 날짜·시간 형식으로 입력하세요.") }

@Controller
@RequestMapping("/admin/experiments/{id}/journeys")
class UserJourneyController(private val experiments: ExperimentService, private val journeys: UserJourneyService) {
    @GetMapping
    fun index(@PathVariable id: Long, @RequestParam(required = false) from: String?,
              @RequestParam(required = false) until: String?, @RequestParam(required = false) userId: String?,
              @RequestParam(required = false) variant: String?, @RequestParam(defaultValue = "ALL") goalState: String,
              @RequestParam(defaultValue = "50") size: Int, @RequestParam(required = false) afterUser: String?,
              @RequestParam(required = false) afterVariant: String?, model: Model): String {
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        val query = JourneyQuery(from?.let(::journeyTime) ?: now.minusDays(30), until?.let(::journeyTime) ?: now,
            userId?.takeUnless(String::isEmpty), variant?.takeUnless(String::isEmpty), goalState, size)
        val experiment = experiments.getExperimentById(id)
        model.addAttribute("experiment", experiment)
        model.addAttribute("hasGoal", !experiment.goalEventName.isNullOrBlank())
        model.addAttribute("query", query)
        model.addAttribute("users", journeys.users(id, query, afterUser, afterVariant))
        model.addAttribute("navigation", JourneyNavigation(JourneyListSource.USERS, query.userId != null,
            query.variant != null, goalState, size, afterUser, afterVariant))
        return "journey/list"
    }

    @GetMapping("/user")
    fun user(@PathVariable id: Long, @RequestParam userId: String, @RequestParam variant: String,
             @RequestParam from: String, @RequestParam until: String, @RequestParam(defaultValue = "50") size: Int,
             @RequestParam(required = false) afterAt: String?, @RequestParam(required = false) afterKind: Int?,
             @RequestParam(required = false) afterId: Long?, @RequestParam(name = "steps", required = false) rawSteps: String?,
             @RequestParam(defaultValue = "24") windowHours: Int, @RequestParam(defaultValue = "1") stage: Int,
             @RequestParam(defaultValue = "REACHED") selection: FunnelSelection,
             @ModelAttribute navigation: JourneyNavigation, request: HttpServletRequest, model: Model): String {
        val query = JourneyQuery(journeyTime(from), journeyTime(until), userId, variant, size = size)
        // Thymeleaf emits empty optional query values in pagination links.
        val steps = rawSteps?.takeUnless(String::isEmpty)
        require(listOf(afterAt, afterKind, afterId).count { it != null } in setOf(0, 3)) { "타임라인의 다음 페이지 조건을 확인하세요." }
        val cursor = afterAt?.let { JourneyCursor(journeyTime(it), afterKind!!, afterId!!) }
        val funnel = steps?.let {
            require(it.length <= 4096) { "퍼널 단계 입력은 4,096자 이하여야 합니다." }
            FunnelQuery(it.lines().filter(String::isNotBlank), query.from, query.until, windowHours).also { funnel ->
                require(stage in 1..funnel.steps.size && (selection != FunnelSelection.MISSING || stage >= 2)) { "조회할 퍼널 단계를 확인하세요." }
            }
        }
        val source = navigation.validated()
        val returnLocation = source.location(request.contextPath, id, query, funnel, stage, selection)
        val details = journeys.details(id, query, cursor, funnel)
        model.addAttribute("navigation", source)
        model.addAttribute("journeyListReturnTo", returnLocation?.takeIf { source.listSource == JourneyListSource.USERS })
        model.addAttribute("funnelListReturnTo", returnLocation?.takeIf { source.listSource == JourneyListSource.FUNNEL })
        model.addAttribute("patternListReturnTo", returnLocation?.takeIf { source.listSource == JourneyListSource.PATTERNS })
        val experiment = experiments.getExperimentById(id)
        model.addAttribute("experiment", experiment)
        model.addAttribute("hasGoal", !experiment.goalEventName.isNullOrBlank())
        model.addAttribute("query", query)
        model.addAttribute("timeline", details.timeline)
        model.addAttribute("funnelUser", details.funnelUser)
        model.addAttribute("funnelQuery", funnel)
        model.addAttribute("steps", steps)
        model.addAttribute("windowHours", windowHours)
        model.addAttribute("stage", stage)
        model.addAttribute("selection", selection.name)
        return "journey/detail"
    }
}
