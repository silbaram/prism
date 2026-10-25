package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*

enum class TargetingAttributeType { STRING, NUMBER, BOOLEAN }

@Entity
@Table(name = "targeting_attributes")
class TargetingAttributeEntity(
    @Id @Column(name = "attribute_name", length = 255) val name: String,
    @Enumerated(EnumType.STRING) @Column(name = "value_type", nullable = false, length = 16) val type: TargetingAttributeType,
    @Column(nullable = false, length = 1000) val description: String = ""
)
