package com.prism.api.domain

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "log_impression")
class ImpressionEntity(
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
class ConversionEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(nullable = false)
    val experimentKey: String,

    @Column(nullable = false)
    val userId: String,

    @Column(nullable = false)
    val eventName: String,

    @Column(nullable = false)
    val timestamp: LocalDateTime = LocalDateTime.now()
)
