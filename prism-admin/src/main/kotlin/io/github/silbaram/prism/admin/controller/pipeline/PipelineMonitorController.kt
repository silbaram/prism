package io.github.silbaram.prism.admin.controller.pipeline

import io.github.silbaram.prism.admin.service.PipelineMonitor
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import jakarta.servlet.http.HttpServletResponse

@Controller
class PipelineMonitorController(private val monitor: PipelineMonitor) {
    @GetMapping("/admin/pipeline")
    fun index(model: Model, response: HttpServletResponse): String {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        model.addAttribute("observation", monitor.observe())
        return "pipeline/index"
    }
}
