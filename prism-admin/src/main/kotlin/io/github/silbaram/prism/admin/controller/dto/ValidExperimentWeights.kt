package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.core.model.validateVariantWeights
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus
import jakarta.validation.Constraint
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext
import jakarta.validation.Payload
import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Constraint(validatedBy = [ExperimentWeightsValidator::class])
annotation class ValidExperimentWeights(
    val message: String = "변형 가중치는 0–100이며 합계는 100이어야 합니다.",
    val groups: Array<KClass<*>> = [],
    val payload: Array<KClass<out Payload>> = []
)

class ExperimentWeightsValidator : ConstraintValidator<ValidExperimentWeights, ExperimentFormDto> {
    override fun isValid(value: ExperimentFormDto?, context: ConstraintValidatorContext): Boolean {
        if (value == null) return true
        // Stopping historical definitions must remain possible. The service still checks
        // all editable definitions and prevents changing frozen weights in these states.
        if (value.status in setOf(ExperimentStatus.PAUSED, ExperimentStatus.ENDED)) return true
        return try { validateVariantWeights(value.variants.map { it.weight }); true }
        catch (_: IllegalArgumentException) { false }
    }
}
