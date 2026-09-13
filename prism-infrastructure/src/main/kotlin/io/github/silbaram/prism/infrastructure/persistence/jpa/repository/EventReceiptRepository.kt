package io.github.silbaram.prism.infrastructure.persistence.jpa.repository

import io.github.silbaram.prism.infrastructure.persistence.jpa.entities.EventReceiptEntity
import org.springframework.data.jpa.repository.JpaRepository

interface EventReceiptRepository : JpaRepository<EventReceiptEntity, String>
