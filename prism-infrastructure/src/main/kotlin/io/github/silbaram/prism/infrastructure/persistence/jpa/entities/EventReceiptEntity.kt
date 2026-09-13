package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.*

/** Committed atomically with the log row. IDs are global across exposures and conversions. */
@Entity
@Table(name = "event_receipts")
class EventReceiptEntity(
    @Id @Column(name = "event_id", length = 36)
    val eventId: String,
    @Column(name = "payload_hash", nullable = false, length = 64)
    val payloadHash: String,
    @Column(name = "config_version", nullable = false, length = 64)
    val configVersion: String
)
