package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.service.*
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*
import java.time.LocalDateTime

@Controller @RequestMapping("/admin/experiments/{id}/analysis")
class AnalysisController(private val service: AdvancedAnalysisService, private val plans: AnalysisPlanService,
                         private val experiments: ExperimentService) {
    @GetMapping fun index(@PathVariable id: Long, model: Model): String {
        model.addAttribute("experiment", experiments.getExperimentById(id))
        model.addAttribute("report", service.report(id))
        model.addAttribute("locked", experiments.isConfigurationLocked(id))
        return "experiment/analysis"
    }
    private fun parseCutoff(value: String): LocalDateTime? = try {
        value.takeIf(String::isNotBlank)?.let(LocalDateTime::parse)
    } catch (_: java.time.format.DateTimeParseException) { throw IllegalArgumentException("사전 데이터 마감 시각은 UTC 날짜·시간 형식이어야 합니다.") }

    @PostMapping("/plan") fun plan(@PathVariable id: Long, @RequestParam controlVariant: String,
        @RequestParam outcomeHours: Int, @RequestParam latenessHours: Int,
        @RequestParam(defaultValue = "") segments: String, @RequestParam(defaultValue = "false") cupedEnabled: Boolean,
        @RequestParam(defaultValue = "") baselineCutoff: String, @RequestParam(defaultValue = "") baselineMetric: String): String {
        val lines = segments.lines().filter(String::isNotBlank)
        require(lines.size <= 5 && lines.all { it.count { char -> char == '=' } == 1 }) { "세그먼트는 key=value1,value2 형식으로 한 줄씩 입력하세요." }
        val entries = lines.map { it.substringBefore('=').trim() to it.substringAfter('=').split(',').map(String::trim) }
        require(entries.map { it.first }.distinct().size == entries.size) { "세그먼트 키는 중복할 수 없습니다." }
        plans.create(id, AnalysisPlanInput(controlVariant, outcomeHours, latenessHours, entries.toMap(), cupedEnabled,
            parseCutoff(baselineCutoff), baselineMetric.takeIf(String::isNotBlank)))
        return "redirect:/admin/experiments/$id/analysis"
    }
}
