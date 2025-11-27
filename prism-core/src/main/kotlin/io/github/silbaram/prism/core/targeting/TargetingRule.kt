package io.github.silbaram.prism.core.targeting

data class TargetingRule(
    val condition: String // SpEL expression, e.g., "user.age >= 20"
)
