package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*

/** A planned event name; registering it never creates an exposure or conversion. */
@Entity
@Table(name = "event_definitions", uniqueConstraints = [UniqueConstraint(name = "uk_event_definition_name", columnNames = ["event_name"])])
class EventDefinitionEntity(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) val id: Long? = null,
    @Column(name = "event_name", nullable = false, length = 255) val name: String,
    @Column(nullable = false, length = 1000) val description: String = ""
)
