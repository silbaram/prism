package io.github.silbaram.prism.admin.controller.experiment

import io.github.silbaram.prism.admin.controller.dto.ExperimentFormDto
import io.github.silbaram.prism.admin.service.AnalyticsService
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.dto.ExperimentCreateDto
import io.github.silbaram.prism.admin.service.dto.ExperimentUpdateDto
import io.github.silbaram.prism.admin.service.dto.TargetingRuleDto
import io.github.silbaram.prism.admin.service.dto.VariantDto
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/admin/experiments")
class AdminExperimentController(
    private val experimentService: ExperimentService,
    private val analyticsService: AnalyticsService
) {

    // 1. 실험 목록 페이지 (페이지네이션 및 검색 기능 포함)
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "10") size: Int,
        @RequestParam(required = false) keyword: String?,
        @RequestParam(required = false) status: ExperimentStatus?,
        model: Model
    ): String {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        val experiments = experimentService.searchExperiments(keyword, status, pageable)

        model.addAttribute("experiments", experiments)
        model.addAttribute("keyword", keyword ?: "")
        model.addAttribute("status", status)
        model.addAttribute("statuses", ExperimentStatus.entries)
        return "experiment/list"
    }

    // 2. 실험 생성 폼 페이지
    @GetMapping("/new")
    fun createForm(model: Model): String {
        model.addAttribute("experiment", ExperimentEntity(key = "", description = ""))
        return "experiment/form"
    }

    // 3. 실험 저장 처리
    @PostMapping
    fun save(@ModelAttribute form: ExperimentFormDto): String {
        val createDto = ExperimentCreateDto(
            key = form.key,
            description = form.description,
            variants = form.variants.map { VariantDto(it.name, it.weight) },
            targetingRules = form.targetingRules.map { TargetingRuleDto(it.expression) }
        )
        experimentService.createExperiment(createDto)
        return "redirect:/admin/experiments"
    }

    // 4. 실험 수정 폼 페이지
    @GetMapping("/{id}/edit")
    fun editForm(@PathVariable id: Long, model: Model): String {
        val experiment = experimentService.getExperimentById(id)
        model.addAttribute("experiment", experiment)
        return "experiment/form"
    }

    // 5. 실험 수정 처리
    @PostMapping("/{id}")
    fun update(@PathVariable id: Long, @ModelAttribute form: ExperimentFormDto): String {
        val updateDto = ExperimentUpdateDto(
            key = form.key,
            description = form.description,
            status = form.status,
            variants = form.variants.map { VariantDto(it.name, it.weight) },
            targetingRules = form.targetingRules.map { TargetingRuleDto(it.expression) }
        )
        experimentService.updateExperiment(id, updateDto)
        return "redirect:/admin/experiments"
    }

    // 6. 실험 삭제 처리
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Long): String {
        experimentService.deleteExperiment(id)
        return "redirect:/admin/experiments"
    }

    // 7. 실험 상세 페이지
    @GetMapping("/{id}")
    fun detail(@PathVariable id: Long, model: Model): String {
        val experiment = experimentService.getExperimentById(id)
        val stats = analyticsService.getExperimentStats(experiment.key)

        model.addAttribute("experiment", experiment)
        model.addAttribute("stats", stats)
        return "experiment/detail"
    }
}
