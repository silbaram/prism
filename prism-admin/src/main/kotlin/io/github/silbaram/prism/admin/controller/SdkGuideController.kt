package io.github.silbaram.prism.admin.controller

import io.github.silbaram.prism.admin.exception.requireValidInput as require
import io.github.silbaram.prism.admin.service.ExperimentService
import io.github.silbaram.prism.admin.service.SdkGuideService
import org.springframework.boot.info.BuildProperties
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

@Controller
@RequestMapping("/admin/experiments/{id}/integration")
class SdkGuideController(private val experiments: ExperimentService, private val guide: SdkGuideService,
                         private val build: BuildProperties) {
    @GetMapping
    fun index(@PathVariable id: Long, @RequestParam(required = false) eventName: String?, model: Model): String {
        val experiment = experiments.getExperimentById(id)
        val selected = eventName ?: experiment.goalEventName.orEmpty()
        require(selected.isEmpty() || (selected.isNotBlank() && selected.length <= 255)) { "이벤트 이름은 1–255자여야 합니다." }
        model.addAttribute("experiment", experiment)
        model.addAttribute("eventName", selected)
        model.addAttribute("sdkVersion", build.version)
        model.addAttribute("examples", selected.takeIf(String::isNotEmpty)?.let { guide.examples(experiment.key, it) })
        return "experiment/integration"
    }
}
