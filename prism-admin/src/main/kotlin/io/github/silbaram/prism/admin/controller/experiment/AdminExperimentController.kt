package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.service.AnalyticsService
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.VariantDto
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.entities.TargetingRuleEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.VariantEntity
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.transaction.annotation.Transactional
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/admin/experiments")
class AdminExperimentController(
    private val experimentRepository: ExperimentRepository,
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

        val experiments = when {
            !keyword.isNullOrBlank() && status != null ->
                experimentRepository.findByKeyContainingAndStatus(keyword, status, pageable)
            !keyword.isNullOrBlank() ->
                experimentRepository.findByKeyContaining(keyword, pageable)
            status != null ->
                experimentRepository.findByStatus(status, pageable)
            else ->
                experimentRepository.findAll(pageable)
        }

        model.addAttribute("experiments", experiments)
        model.addAttribute("keyword", keyword ?: "")
        model.addAttribute("status", status)
        model.addAttribute("statuses", ExperimentStatus.values())
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
    @Transactional
    fun save(@RequestParam key: String,
             @RequestParam description: String,
             @RequestParam status: ExperimentStatus,
             @RequestParam("variants[].name") variantNames: List<String>,
             @RequestParam("variants[].weight") variantWeights: List<Int>,
             @RequestParam("targetingRules[].expression", required = false) targetingRuleExpressions: List<String>?): String {

        // Variant DTO 리스트 생성
        val variants = variantNames.indices.map { i ->
            VariantDto(variantNames[i], variantWeights[i])
        }

        // ExperimentService를 통해 생성
        val experiment = experimentService.createExperiment(key, description, variants)

        // Targeting Rules 추가
        targetingRuleExpressions?.filterNot { it.isBlank() }?.forEach { expression ->
            val ruleEntity = TargetingRuleEntity(expression = expression)
            experiment.addTargetingRule(ruleEntity)
        }

        experimentRepository.save(experiment)

        return "redirect:/admin/experiments"
    }

    // 4. 실험 수정 폼 페이지
    @GetMapping("/{id}/edit")
    @Transactional(readOnly = true)
    fun editForm(@PathVariable id: Long, model: Model): String {
        val experiment = experimentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다. ID: $id") }
        model.addAttribute("experiment", experiment)
        return "experiment/form"
    }

    // 5. 실험 수정 처리
    @PostMapping("/{id}")
    @Transactional
    fun update(@PathVariable id: Long,
               @RequestParam key: String,
               @RequestParam description: String,
               @RequestParam status: ExperimentStatus,
               @RequestParam("variants[].name") variantNames: List<String>,
               @RequestParam("variants[].weight") variantWeights: List<Int>,
               @RequestParam("targetingRules[].expression", required = false) targetingRuleExpressions: List<String>?): String {

        val existingExperiment = experimentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다. ID: $id") }

        existingExperiment.key = key
        existingExperiment.description = description
        existingExperiment.status = status
        existingExperiment.updatedAt = java.time.LocalDateTime.now()

        // 기존 variants 삭제하고 새로 추가
        existingExperiment.variants.clear()
        variantNames.indices.forEach { i ->
            val variantEntity = VariantEntity(
                name = variantNames[i],
                weight = variantWeights[i]
            )
            existingExperiment.addVariant(variantEntity)
        }

        // 기존 targeting rules 삭제하고 새로 추가
        existingExperiment.targetingRules.clear()
        targetingRuleExpressions?.filterNot { it.isBlank() }?.forEach { expression ->
            val ruleEntity = TargetingRuleEntity(expression = expression)
            existingExperiment.addTargetingRule(ruleEntity)
        }

        experimentRepository.save(existingExperiment)
        return "redirect:/admin/experiments"
    }

    // 6. 실험 삭제 처리
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Long): String {
        experimentRepository.deleteById(id)
        return "redirect:/admin/experiments"
    }

    // 7. 실험 상세 페이지
    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    fun detail(@PathVariable id: Long, model: Model): String {
        val experiment = experimentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다. ID: $id") }

        val stats = analyticsService.getExperimentStats(experiment.key)

        model.addAttribute("experiment", experiment)
        model.addAttribute("stats", stats)
        return "experiment/detail"
    }
}
