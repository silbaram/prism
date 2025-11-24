package io.github.silbaram.prism.admin.api.domain

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "log_impression")
class ImpressionLogEntity(
    @Id
    val id: Long? = null,
    val experimentKey: String,
    val variant: String,
    val userId: String,
    val timestamp: LocalDateTime
)

@Entity
@Table(name = "log_conversion")
class ConversionLogEntity(
    @Id
    val id: Long? = null,
    val experimentKey: String,
    val userId: String,
    val eventName: String,
    val timestamp: LocalDateTime
)
