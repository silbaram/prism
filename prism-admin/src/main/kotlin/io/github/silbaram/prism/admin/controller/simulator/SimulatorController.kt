package io.github.silbaram.prism.admin.controller.simulator

import io.github.silbaram.prism.admin.service.SimulatorService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam

@Controller
@RequestMapping("/admin/simulator")
class SimulatorController(
    private val simulatorService: SimulatorService
) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("experiments", simulatorService.getAllExperimentSummaries())
        return "simulator/index"
    }

    @PostMapping("/test")
    fun test(
        @RequestParam experimentId: Long,
        @RequestParam userId: String,
        model: Model
    ): String {
        val result = simulatorService.simulateAssignment(experimentId, userId)

        model.addAttribute("experiments", simulatorService.getAllExperimentSummaries())
        model.addAttribute("selectedExperimentId", experimentId)
        model.addAttribute("userId", userId)
        model.addAttribute("result", result.assignedVariant)

        return "simulator/index"
    }
}
