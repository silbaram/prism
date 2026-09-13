package io.github.silbaram.prism.infrastructure.persistence.jpa.entities

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import java.sql.Timestamp
import java.time.LocalDateTime
import java.time.ZoneOffset

/** Log LocalDateTime values represent UTC; never interpret them using the JVM default zone. */
@Converter
class UtcLogTimestampConverter : AttributeConverter<LocalDateTime, Timestamp> {
    override fun convertToDatabaseColumn(value: LocalDateTime?): Timestamp? =
        value?.toInstant(ZoneOffset.UTC)?.let(Timestamp::from)

    override fun convertToEntityAttribute(value: Timestamp?): LocalDateTime? =
        value?.toInstant()?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
}
