package io.github.silbaram.prism.admin.page.controller

import io.github.silbaram.prism.admin.page.model.ExperimentEntity
import io.github.silbaram.prism.admin.page.repository.ExperimentRepository
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ModelAttribute
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping

@Controller
@RequestMapping("/admin/experiments")
class AdminExperimentController(
    private val experimentRepository: ExperimentRepository
) {

    // 1. 실험 목록 페이지
    @GetMapping
    fun list(model: Model): String {
        val experiments = experimentRepository.findAll()
        model.addAttribute("experiments", experiments)
        return "experiment/list" // templates/experiment/list.html을 찾아감
    }

    // 2. 실험 생성 폼 페이지
    @GetMapping("/new")
    fun createForm(model: Model): String {
        model.addAttribute("experiment", ExperimentEntity(key = "", variants = mutableListOf("A", "B")))
        return "experiment/form"
    }

    // 3. 실험 저장 처리
    @PostMapping
    fun save(@ModelAttribute experiment: ExperimentEntity): String {
        experimentRepository.save(experiment)
        return "redirect:/admin/experiments" // 저장 후 목록으로 이동
    }
}
