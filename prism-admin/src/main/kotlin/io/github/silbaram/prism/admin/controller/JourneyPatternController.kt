package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.controller.dto.JourneyListSource
import io.github.silbaram.prism.admin.controller.dto.JourneyNavigation
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.JourneyPatternQuery
import io.github.silbaram.prism.admin.service.JourneyPatternService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

@Controller
class JourneyPatternController(private val experiments: ExperimentService, private val patterns: JourneyPatternService) {
    @GetMapping("/admin/experiments/{id}/journeys/patterns")
    fun index(@PathVariable id: Long, @RequestParam(required = false) from: String?,
        @RequestParam(required = false) until: String?, @RequestParam(required = false) variant: String?,
        @RequestParam(defaultValue = "5") depth: Int, @RequestParam(defaultValue = "20") top: Int, model: Model): String {
        val now = LocalDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS)
        val query = JourneyPatternQuery(from?.let(::journeyTime) ?: now.minusDays(30), until?.let(::journeyTime) ?: now,
            variant?.takeUnless(String::isEmpty), depth, top)
        val experiment = experiments.getExperimentById(id)
        val report = patterns.report(id, query)
        model.addAttribute("experiment", experiment)
        model.addAttribute("query", query)
        model.addAttribute("report", report)
        model.addAttribute("navigation", JourneyNavigation(listSource = JourneyListSource.PATTERNS,
            listFilterVariant = query.variant != null, patternDepth = depth, patternTop = top))
        return "journey/patterns"
    }
}
