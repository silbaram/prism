
package com.prism.admin.controller

import com.prism.admin.service.AnalyticsService
import com.prism.admin.service.ExperimentStats
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(
    private val analyticsService: AnalyticsService
) {

    @GetMapping("/{experimentKey}")
    fun getStats(@PathVariable experimentKey: String): ExperimentStats {
        return analyticsService.getExperimentStats(experimentKey)
    }
}
