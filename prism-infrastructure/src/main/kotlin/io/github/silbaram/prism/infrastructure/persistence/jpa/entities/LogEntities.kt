package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "log_impression")
class ImpressionLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val experimentKey: String,

    @Column(nullable = false)
    val variant: String,

    @Column(nullable = false)
    val userId: String,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now()
)

@Entity
@Table(name = "log_conversion")
class ConversionLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val experimentKey: String,

    @Column(nullable = false)
    val variant: String,

    @Column(nullable = false)
    val userId: String,

    @Column(nullable = false)
    val eventName: String,

    // Null only for historical events whose exposure was not recorded explicitly.
    @Column(name = "impression_id")
    val impressionId: Long? = null,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now()
)
