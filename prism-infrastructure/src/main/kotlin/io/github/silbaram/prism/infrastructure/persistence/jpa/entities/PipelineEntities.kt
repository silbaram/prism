package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*
import java.time.LocalDateTime
import java.time.ZoneOffset

@Entity @Table(name = "pipeline_inbox")
class PipelineInboxEntity(
    @Id @Column(length = 64) val id: String,
    @Column(name = "event_id", nullable = false, length = 36) val eventId: String,
    @Column(nullable = false, columnDefinition = "LONGTEXT") val payload: String,
    @Column(nullable = false, length = 16) var status: String = "PENDING",
    @Column(nullable = false) var attempts: Int = 0,
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "retry_at", nullable = false)
    var retryAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC),
    @Convert(converter = UtcLogTimestampConverter::class) @Column(name = "created_at", nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(ZoneOffset.UTC)
)

@Entity @Table(name = "pipeline_outbox")
class PipelineOutboxEntity(
    @Id @Column(length = 64) val id: String,
    @Column(nullable = false, length = 16) val kind: String,
    @Column(nullable = false, columnDefinition = "LONGTEXT") val payload: String
)
