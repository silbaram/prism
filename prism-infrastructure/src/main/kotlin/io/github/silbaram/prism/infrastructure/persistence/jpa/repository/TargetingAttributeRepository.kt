package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.TargetingAttributeEntity
import org.springframework.data.jpa.repository.JpaRepository

interface TargetingAttributeRepository : JpaRepository<TargetingAttributeEntity, String>
