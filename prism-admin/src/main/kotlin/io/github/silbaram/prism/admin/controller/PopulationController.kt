package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.service.PopulationService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller @RequestMapping("/admin/population")
class PopulationController(private val population: PopulationService,
                           private val exposures: io.github.silbaram.prism.infrastructure.persistence.jpa.repository.PopulationExposureRepository,
                           private val conversions: io.github.silbaram.prism.infrastructure.persistence.jpa.repository.PopulationConversionRepository) {
    @GetMapping fun index(model: Model): String {
        model.addAttribute("policy", population.policy())
        model.addAttribute("layers", population.layers())
        model.addAttribute("changes", population.history())
        val key = population.policy().holdoutKey
        model.addAttribute("cohortUsers", exposures.users(key))
        model.addAttribute("cohortOutcomes", conversions.outcomes(key))
        return "population/index"
    }
    @PostMapping("/holdout") fun holdout(@RequestParam key: String, @RequestParam basisPoints: Int): String {
        population.configureHoldout(key, basisPoints)
        return "redirect:/admin/population"
    }
    @PostMapping("/layers") fun layer(@RequestParam key: String, @RequestParam(defaultValue = "") description: String): String {
        population.createLayer(key, description)
        return "redirect:/admin/population"
    }
}
