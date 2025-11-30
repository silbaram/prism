package io.github.silbaram.prism.admin.page.controller

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentStatus
import io.github.silbaram.prism.infrastructure.persistence.repository.ExperimentRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/admin/experiments")
class AdminExperimentController(
    private val experimentRepository: ExperimentRepository
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
    fun save(@ModelAttribute experiment: ExperimentEntity): String {
        experimentRepository.save(experiment)
        return "redirect:/admin/experiments"
    }

    // 4. 실험 수정 폼 페이지
    @GetMapping("/{id}/edit")
    fun editForm(@PathVariable id: Long, model: Model): String {
        val experiment = experimentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다. ID: $id") }
        model.addAttribute("experiment", experiment)
        return "experiment/form"
    }

    // 5. 실험 수정 처리
    @PostMapping("/{id}")
    fun update(@PathVariable id: Long, @ModelAttribute experiment: ExperimentEntity): String {
        val existingExperiment = experimentRepository.findById(id)
            .orElseThrow { IllegalArgumentException("실험을 찾을 수 없습니다. ID: $id") }

        existingExperiment.key = experiment.key
        existingExperiment.description = experiment.description
        existingExperiment.status = experiment.status
        existingExperiment.updatedAt = java.time.LocalDateTime.now()

        experimentRepository.save(existingExperiment)
        return "redirect:/admin/experiments"
    }

    // 6. 실험 삭제 처리
    @PostMapping("/{id}/delete")
    fun delete(@PathVariable id: Long): String {
        experimentRepository.deleteById(id)
        return "redirect:/admin/experiments"
    }
}
