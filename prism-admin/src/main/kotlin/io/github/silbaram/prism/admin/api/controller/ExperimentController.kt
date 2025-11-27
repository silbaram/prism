package io.github.silbaram.prism.admin.api.controller

import io.github.silbaram.prism.infrastructure.persistence.entities.ExperimentEntity
import io.github.silbaram.prism.admin.api.service.ExperimentService
import io.github.silbaram.prism.admin.api.service.VariantDto
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/experiments")
class ExperimentController(
    private val experimentService: ExperimentService
) {

    @PostMapping
    fun createExperiment(@RequestBody request: CreateExperimentRequest): ExperimentEntity {
        return experimentService.createExperiment(
            request.key,
            request.description,
            request.variants.map { VariantDto(it.name, it.weight) }
        )
    }

    @GetMapping
    fun getAllExperiments(): List<ExperimentEntity> {
        return experimentService.getAllExperiments()
    }

    @PostMapping("/{id}/start")
    fun startExperiment(@PathVariable id: Long): ExperimentEntity {
        return experimentService.startExperiment(id)
    }

    @PostMapping("/{id}/pause")
    fun pauseExperiment(@PathVariable id: Long): ExperimentEntity {
        return experimentService.pauseExperiment(id)
    }
}

data class CreateExperimentRequest(
    val key: String,
    val description: String,
    val variants: List<VariantRequest>
)

data class VariantRequest(
    val name: String,
    val weight: Int
)
