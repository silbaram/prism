package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*

@Entity @Table(name = "sticky_assignments")
class StickyAssignmentEntity(
    @Id @Column(length = 64) val id: String,
    @Column(name = "experiment_key", nullable = false) val experimentKey: String,
    @Column(name = "user_id", nullable = false) val userId: String,
    @Column(nullable = false) val variant: String
)
