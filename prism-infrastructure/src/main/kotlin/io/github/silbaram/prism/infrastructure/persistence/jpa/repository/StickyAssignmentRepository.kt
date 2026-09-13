package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.StickyAssignmentEntity
import org.springframework.data.jpa.repository.JpaRepository

interface StickyAssignmentRepository : JpaRepository<StickyAssignmentEntity, String>
