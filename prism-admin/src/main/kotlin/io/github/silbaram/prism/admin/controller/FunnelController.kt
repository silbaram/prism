package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.exception.AdminValidationException
import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.FunnelAnalysisService
import io.github.silbaram.prism.admin.service.FunnelQuery
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Controller
@RequestMapping("/admin/experiments/{id}/funnel")
class FunnelController(private val experiments: ExperimentService, private val funnels: FunnelAnalysisService) {
    @GetMapping
    fun index(@PathVariable id: Long, @RequestParam(required = false) steps: String?,
              @RequestParam(required = false) from: String?, @RequestParam(required = false) until: String?,
              @RequestParam(defaultValue = "24") windowHours: Int, model: Model): String {
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        model.addAttribute("experiment", experiments.getExperimentById(id))
        model.addAttribute("steps", steps.orEmpty())
        model.addAttribute("from", from ?: now.minusDays(30).toString())
        model.addAttribute("until", until ?: now.toString())
        model.addAttribute("windowHours", windowHours)
        model.addAttribute("report", null)
        if (steps != null) {
            require(steps.length <= 4096) { "이벤트를 한 줄씩 2–8개 입력하세요." }
            val query = FunnelQuery(steps.lines().filter(String::isNotBlank), parseTime(from, "조회 시작"),
                parseTime(until, "조회 종료"), windowHours)
            model.addAttribute("report", funnels.report(id, query))
        }
        return "experiment/funnel"
    }

    private fun parseTime(value: String?, label: String): LocalDateTime = try {
        value?.takeIf(String::isNotBlank)?.let(LocalDateTime::parse)
            ?: throw AdminValidationException("${label} 시각을 UTC 날짜·시간 형식으로 입력하세요.")
    } catch (_: java.time.format.DateTimeParseException) {
        throw AdminValidationException("${label} 시각을 UTC 날짜·시간 형식으로 입력하세요.")
    }
}
