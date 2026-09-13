package io.github.silbaram.prism.admin.controller.dto

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentEntity
import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.ExperimentStatus

/** Raw strings preserve rejected numeric/date input without mutating managed JPA entities. */
data class ExperimentFormView(
    val id: Long?, val key: String, val description: String, val goalEventName: String,
    val status: ExperimentStatus, val selectedStatus: String, val configurationLocked: Boolean,
    val trafficAllocation: String, val minimumAllocation: Int,
    val startsAt: String, val endsAt: String, val guardrailEvents: String,
    val variants: List<VariantInput>, val targetingRules: List<RuleInput>
) {
    data class VariantInput(val name: String, val weight: String)
    data class RuleInput(val expression: String)

    companion object {
        fun from(entity: ExperimentEntity, locked: Boolean, input: Map<String, String>? = null): ExperimentFormView {
            fun value(name: String, fallback: String) = if (input == null) fallback else input[name].orEmpty()
            fun indices(collection: String, limit: Int) = input.orEmpty().keys.mapNotNull {
                Regex("$collection\\[([0-9]+)]\\..+").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull()
            }.filter { it in 0 until limit }.toSortedSet()
            return ExperimentFormView(entity.id, value("key", entity.key), value("description", entity.description),
                value("goalEventName", entity.goalEventName.orEmpty()), entity.status,
                if (input == null) entity.status.name else input["status"] ?: "DRAFT", locked,
                if (input == null) entity.trafficAllocation.toString() else input["trafficAllocation"] ?: "100",
                if (locked) entity.trafficAllocation else 0,
                value("startsAt", entity.startsAt?.toString().orEmpty()), value("endsAt", entity.endsAt?.toString().orEmpty()),
                value("guardrailEvents", entity.guardrailEventNames.sorted().joinToString("\n")),
                if (input != null) indices("variants", 256).map { VariantInput(value("variants[$it].name", ""), value("variants[$it].weight", "")) }
                else if (entity.id == null) listOf(VariantInput("", "50"), VariantInput("", "50"))
                else entity.variants.map { VariantInput(it.name, it.weight.toString()) },
                if (input != null) indices("targetingRules", 100).map { RuleInput(value("targetingRules[$it].expression", "")) }
                else entity.targetingRules.map { RuleInput(it.expression) })
        }
    }
}
