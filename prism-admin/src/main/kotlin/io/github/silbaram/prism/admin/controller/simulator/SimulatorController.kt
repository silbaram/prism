package io.github.silbaram.prism.admin.controller.simulator

import io.github.silbaram.prism.admin.service.SimulatorService
import io.github.silbaram.prism.admin.service.ExperimentService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import io.github.silbaram.prism.admin.service.PreviewMode

@Controller
@RequestMapping("/admin/simulator")
class SimulatorController(
    private val simulatorService: SimulatorService,
    private val experimentService: ExperimentService
) {

    @GetMapping
    fun index(model: Model): String {
        model.addAttribute("experiments", experimentService.getAllExperiments())
        return "simulator/index"
    }

    @PostMapping("/test")
    fun test(
        @RequestParam experimentId: Long,
        @RequestParam userId: String,
        @RequestParam(defaultValue = "{}") attributes: String,
        @RequestParam(defaultValue = "CURRENT") previewMode: PreviewMode,
        model: Model
    ): String {
        model.addAttribute("experiments", experimentService.getAllExperiments())
        model.addAttribute("selectedExperimentId", experimentId)
        model.addAttribute("userId", userId)
        model.addAttribute("attributes", attributes)
        model.addAttribute("previewMode", previewMode.name)
        try {
            val result = simulatorService.simulateAssignment(experimentId, userId, attributes, previewMode)
            model.addAttribute("result", result.assignedVariant)
            model.addAttribute("simulation", result)
            model.addAttribute("simulationCompleted", true)
        } catch (error: IllegalArgumentException) {
            model.addAttribute("simulationError", error.message ?: "입력값을 확인하세요.")
        }

        return "simulator/index"
    }
}
